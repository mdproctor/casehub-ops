package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationSource;
import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ops.deployment.DeploymentGoalCompiler;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Central coordinator that connects RAS situations to topology adaptation.
 * <p>
 * Sits between goal loading and the reconciliation loop, re-compiling and pushing
 * graph updates when situations change. Each tenant's state (goals, rules, hysteresis)
 * is tracked independently in a {@link TenantAdaptationState} stored in a
 * {@link ConcurrentHashMap}.
 * <p>
 * Two trigger paths:
 * <ol>
 *   <li><b>CDI event-driven:</b> {@code @ObservesAsync SituationChangeEvent} triggers
 *       immediate recompilation for the affected tenant.</li>
 *   <li><b>Periodic re-poll:</b> a safety net that polls all tenants every 5 minutes,
 *       catching situations that were activated without a corresponding CDI event.</li>
 * </ol>
 * <p>
 * Thread safety: per-tenant serialization via {@code synchronized(state)} ensures
 * hysteresis state mutations are atomic. Cross-tenant access uses the
 * {@link ConcurrentHashMap} for safe concurrent lookup.
 */
@ApplicationScoped
public class AdaptiveTopologyManager {

    private static final Logger LOG = Logger.getLogger(AdaptiveTopologyManager.class.getName());
    private static final long POLL_INTERVAL_MINUTES = 5;

    private final DeploymentGoalCompiler compiler;
    private final DesiredStateGraphFactory graphFactory;
    private final ObjectMapper mapper;
    private final SituationSource situationSource;
    private final ReconciliationTarget reconciliationTarget;

    private final ConcurrentHashMap<String, TenantAdaptationState> tenantStates =
        new ConcurrentHashMap<>();

    /**
     * Tracks the last compiled graph per tenant for change detection in periodic re-poll.
     */
    private final ConcurrentHashMap<String, DesiredStateGraph> lastCompiledGraphs =
        new ConcurrentHashMap<>();

    private final ScheduledExecutorService pollScheduler;
    private volatile ScheduledFuture<?> pollFuture;

    /**
     * CDI and test constructor.
     * <p>
     * In production, a {@link ReconciliationTarget} bean is provided by the application
     * assembly layer (e.g. casehub-desiredstate runtime adapter). In tests, a spy
     * implementation is passed directly.
     */
    @Inject
    public AdaptiveTopologyManager(
            DeploymentGoalCompiler compiler,
            DesiredStateGraphFactory graphFactory,
            ObjectMapper mapper,
            SituationSource situationSource,
            ReconciliationTarget reconciliationTarget) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.graphFactory = Objects.requireNonNull(graphFactory, "graphFactory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.situationSource = Objects.requireNonNull(situationSource, "situationSource");
        this.reconciliationTarget = Objects.requireNonNull(reconciliationTarget, "reconciliationTarget");
        this.pollScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "adaptive-topology-poll");
            t.setDaemon(true);
            return t;
        });
        startPeriodicPoll();
    }

    /**
     * Initializes adaptive topology management for a tenant.
     * <p>
     * Parses adaptation rules from the goals, compiles the initial topology
     * (applying any already-active situations), and starts the reconciliation loop.
     *
     * @param tenancyId the tenant identifier
     * @param goals     the deployment goals including adaptation rules
     */
    public void initialize(String tenancyId, DeploymentGoals goals) {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(goals, "goals");

        List<AdaptationRule> rules = AdaptationRule.fromSpecs(
            goals.adaptations(), compiler, mapper, graphFactory);
        var state = new TenantAdaptationState(goals, rules);

        DesiredStateGraph adapted;
        synchronized (state) {
            adapted = compileAdapted(tenancyId, state);
        }
        lastCompiledGraphs.put(tenancyId, adapted);
        reconciliationTarget.start(tenancyId, adapted);
        tenantStates.put(tenancyId, state);
    }

    /**
     * Handles situation change events. Re-compiles the adapted topology for the
     * affected tenant and pushes the new graph to the reconciliation loop.
     * <p>
     * {@code @ObservesAsync} runs on Vert.x worker pool threads with no per-observer
     * serialization. Per-tenant locking via {@code synchronized(state)} ensures
     * hysteresis state is mutated atomically.
     */
    public void onSituationChange(@ObservesAsync SituationChangeEvent event) {
        String tenancyId = event.tenancyId();
        TenantAdaptationState state = tenantStates.get(tenancyId);
        if (state == null) {
            return;
        }

        DesiredStateGraph adapted;
        synchronized (state) {
            adapted = compileAdapted(tenancyId, state);
        }
        lastCompiledGraphs.put(tenancyId, adapted);
        reconciliationTarget.updateDesired(tenancyId, adapted);
        reconciliationTarget.requestReconciliation(tenancyId);
    }

    /**
     * Polls all tenants for situation changes as a safety net for lost CDI events.
     * <p>
     * Compares the recompiled graph against the last known graph. If different,
     * pushes the update to the reconciliation loop.
     */
    void pollAllTenants() {
        for (var entry : tenantStates.entrySet()) {
            String tenancyId = entry.getKey();
            TenantAdaptationState state = entry.getValue();

            DesiredStateGraph adapted;
            synchronized (state) {
                adapted = compileAdapted(tenancyId, state);
            }

            DesiredStateGraph previous = lastCompiledGraphs.get(tenancyId);
            if (!graphsEqual(adapted, previous)) {
                lastCompiledGraphs.put(tenancyId, adapted);
                reconciliationTarget.updateDesired(tenancyId, adapted);
                reconciliationTarget.requestReconciliation(tenancyId);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (pollFuture != null) {
            pollFuture.cancel(false);
        }
        pollScheduler.shutdownNow();
    }

    // --- private ---

    private DesiredStateGraph compileAdapted(String tenancyId, TenantAdaptationState state) {
        DesiredStateGraph base = ((CompilationResult.SingleGraph) compiler.compile(state.goals(), graphFactory)).graph();
        if (state.rules().isEmpty()) {
            return base;
        }

        List<ActiveSituation> situations = situationSource.activeSituations(tenancyId)
            .await().indefinitely();

        Set<String> activeSituationIds = situations.stream()
            .map(ActiveSituation::situationId)
            .collect(Collectors.toSet());

        DesiredStateGraph adapted = base;
        Set<NodeId> modifiedNodes = new HashSet<>();

        for (AdaptationRule rule : state.rules()) {
            Optional<ActiveSituation> match = situations.stream()
                .filter(s -> s.situationId().equals(rule.trigger().situation()))
                .filter(s -> state.shouldActivate(rule, s))
                .findFirst();

            if (match.isPresent()) {
                Set<NodeId> targets = rule.targetNodeIds(base);
                for (NodeId t : targets) {
                    if (modifiedNodes.contains(t)) {
                        LOG.warning(String.format(
                            "Conflict: rule '%s' modifies node '%s' "
                                + "already modified by an earlier rule",
                            rule.name(), t.value()));
                    }
                }
                adapted = rule.apply(adapted, match.get());
                modifiedNodes.addAll(targets);
            }
        }

        state.clearAbsentSituations(activeSituationIds);

        return adapted;
    }

    private void startPeriodicPoll() {
        pollFuture = pollScheduler.scheduleAtFixedRate(
            () -> {
                try {
                    pollAllTenants();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Periodic situation poll failed", e);
                }
            },
            POLL_INTERVAL_MINUTES,
            POLL_INTERVAL_MINUTES,
            TimeUnit.MINUTES);
    }

    /**
     * Compares two graphs by their node sets and dependency sets.
     * Graph version numbers are not meaningful for equality — they increment
     * on every mutation. Content equality is what matters.
     */
    private static boolean graphsEqual(DesiredStateGraph a, DesiredStateGraph b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.nodes().equals(b.nodes()) && a.dependencies().equals(b.dependencies());
    }

    /**
     * Abstraction over the three reconciliation loop operations needed by this manager.
     * <p>
     * In production, a CDI bean adapts the runtime's {@code ReconciliationLoop} to this
     * interface (the runtime module is not a compile dependency of the deployment module).
     * In tests, a spy implementation records calls for verification.
     */
    public interface ReconciliationTarget {
        void start(String tenancyId, DesiredStateGraph desired);
        void updateDesired(String tenancyId, DesiredStateGraph newDesired);
        void requestReconciliation(String tenancyId);
    }
}
