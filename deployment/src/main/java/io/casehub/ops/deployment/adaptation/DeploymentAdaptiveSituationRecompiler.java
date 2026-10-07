package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.ops.api.deployment.AdaptationSnapshotProvider;
import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ops.deployment.DeploymentGoalCompiler;
import io.casehub.ras.api.ActiveSituation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class DeploymentAdaptiveSituationRecompiler implements SituationRecompiler, AdaptationSnapshotProvider {

    private static final Logger                                                             LOG                = Logger.getLogger(
            DeploymentAdaptiveSituationRecompiler.class.getName());
    static final         Map<String, Class<? extends io.casehub.desiredstate.api.NodeSpec>> NODE_TYPE_REGISTRY = Map.of(
            "agent", io.casehub.ops.api.deployment.AgentNodeSpec.class,
            "channel", io.casehub.ops.api.deployment.ChannelNodeSpec.class,
            "case_type", io.casehub.ops.api.deployment.CaseTypeNodeSpec.class,
            "trust_policy", io.casehub.ops.api.deployment.TrustPolicyNodeSpec.class,
            "endpoint", io.casehub.ops.api.deployment.EndpointNodeSpec.class,
            "detection", io.casehub.ops.api.deployment.DetectionNodeSpec.class,
            "pool", io.casehub.ops.api.deployment.PoolNodeSpec.class
                                                                                                                       );


    @Inject
    DeploymentGoalCompiler compiler;
    @Inject
    ObjectMapper           mapper;
    @Inject
    MeterRegistry          meterRegistry;

    private final ConcurrentHashMap<String, TenantAdaptationState> tenantStates =
            new ConcurrentHashMap<>();

    @Override
    public int priority() {
        return 100;
    }

    public void register(String tenancyId, DeploymentGoals goals,
                         Map<String, Duration> situationClearanceWindows,
                         DesiredStateGraphFactory factory) {
        List<AdaptationRule> rules = AdaptationRule.fromSpecs(
                goals.adaptations(), compiler, mapper, factory, NODE_TYPE_REGISTRY);
        var state = new TenantAdaptationState(goals, rules, situationClearanceWindows);
        tenantStates.put(tenancyId, state);
    }

    @Override
    public Optional<CompilationResult> recompile(
            String tenancyId,
            DesiredStateGraph currentGraph,
            ActualState actualState,
            ActiveSituation situation,
            DesiredStateGraphFactory factory) {

        TenantAdaptationState state = tenantStates.get(tenancyId);
        if (state == null || state.rules().isEmpty()) {
            return Optional.empty();
        }

        synchronized (state) {
            state.updateSituation(situation);

            CompilationResult baseResult    = compiler.compile(state.goals(), factory);
            DesiredStateGraph base          = ((CompilationResult.SingleGraph) baseResult).graph();
            DesiredStateGraph adapted       = base;
            Set<NodeId>       modifiedNodes = new HashSet<>();

            for (AdaptationRule rule : state.rules()) {
                Optional<ActiveSituation> match = state.activeSituationFor(rule);
                if (match.isPresent() && state.shouldActivate(rule, match.get())) {
                    Set<NodeId> targets = rule.targetNodeIds(base);
                    for (NodeId t : targets) {
                        if (modifiedNodes.contains(t)) {
                            LOG.warning(String.format(
                                    "Conflict: rule '%s' modifies '%s' "
                                    + "already modified by earlier rule",
                                    rule.name(), t.value()));
                            meterRegistry.counter("desiredstate.adaptation.conflict.total",
                                                  Tags.of("tenancy_id", tenancyId, "rule_name", rule.name(), "node_id", t.value()))
                                         .increment();
                        }
                    }
                    adapted = rule.apply(adapted, match.get());
                    modifiedNodes.addAll(targets);
                }
            }

            state.clearAbsentSituations();

            if (graphsEqual(adapted, base)) {
                return Optional.empty();
            }
            return Optional.of(CompilationResult.single(adapted));
        }
    }

    @Override
    public Optional<CompilationResult> situationResolved(
            String tenancyId,
            String situationId,
            DesiredStateGraph currentGraph,
            ActualState actualState,
            DesiredStateGraphFactory factory) {

        TenantAdaptationState state = tenantStates.get(tenancyId);
        if (state == null) {
            return Optional.empty();
        }

        synchronized (state) {
            boolean hadSituation = state.clearSituation(situationId);
            if (!hadSituation) {
                return Optional.empty();
            }

            CompilationResult baseResult = compiler.compile(state.goals(), factory);
            DesiredStateGraph base       = ((CompilationResult.SingleGraph) baseResult).graph();
            DesiredStateGraph adapted    = base;

            for (AdaptationRule rule : state.rules()) {
                Optional<ActiveSituation> match = state.activeSituationFor(rule);
                if (match.isPresent() && state.shouldActivate(rule, match.get())) {
                    adapted = rule.apply(adapted, match.get());
                }
            }

            if (graphsEqual(adapted, currentGraph)) {
                return Optional.empty();
            }
            return Optional.of(CompilationResult.single(adapted));
        }
    }

    @Override
    public AdaptationSnapshot getAdaptationSnapshot(String tenancyId) {
        TenantAdaptationState state = tenantStates.get(tenancyId);
        if (state == null) {
            return new AdaptationSnapshot(List.of(), List.of());
        }

        synchronized (state) {
            List<AdaptationSnapshotProvider.ActiveRuleInfo> ruleInfos =
                    state.rules().stream()
                         .map(rule -> new AdaptationSnapshotProvider.ActiveRuleInfo(
                                 rule.name(),
                                 rule.trigger().situation(),
                                 state.isRuleActive(rule.name())))
                         .toList();

            List<AdaptationSnapshotProvider.TrackedSituationInfo> sitInfos =
                    state.trackedSituationSnapshot().stream()
                         .map(sit -> new AdaptationSnapshotProvider.TrackedSituationInfo(
                                 sit.situationId(), sit.confidence(),
                                 sit.since(), sit.lastSignal()))
                         .toList();

            return new AdaptationSnapshot(ruleInfos, sitInfos);
        }
    }

    private static boolean graphsEqual(DesiredStateGraph a, DesiredStateGraph b) {
        if (a == b) {return true;}
        if (a == null || b == null) {return false;}
        return a.nodes().equals(b.nodes()) && a.dependencies().equals(b.dependencies());
    }
}
