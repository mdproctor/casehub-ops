package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ops.app.case_.ScalingPolicy;
import io.casehub.ops.app.model.ScalingRule;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationSource;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class SituationScalingEvaluator {

    private static final Logger LOG = Logger.getLogger(SituationScalingEvaluator.class.getName());
    private static final long POLL_INTERVAL_MINUTES = 5;

    record ScalingRegistration(UUID appCaseId, String applicationId,
                               Map<String, Integer> baseReplicas) {}

    private final SituationSource situationSource;
    private final Consumer<ScalingRequestedEvent> eventSink;
    private final Function<String, String> servicesJsonLoader;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ScalingRegistration> registrations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastScalingTimestamps = new ConcurrentHashMap<>();

    private final ScheduledExecutorService pollScheduler;
    private volatile ScheduledFuture<?> pollFuture;

    @Inject
    public SituationScalingEvaluator(
            SituationSource situationSource,
            Event<ScalingRequestedEvent> cdiEvent,
            ScalingEvaluatorSupport support,
            ObjectMapper objectMapper) {
        this(situationSource,
             event -> cdiEvent.fireAsync(event),
             appId -> support.loadServicesJson(UUID.fromString(appId)),
             objectMapper);
    }

    SituationScalingEvaluator(
            SituationSource situationSource,
            Consumer<ScalingRequestedEvent> eventSink,
            Function<String, String> servicesJsonLoader,
            ObjectMapper objectMapper) {
        this.situationSource = Objects.requireNonNull(situationSource);
        this.eventSink = Objects.requireNonNull(eventSink);
        this.servicesJsonLoader = Objects.requireNonNull(servicesJsonLoader);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.pollScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "scaling-evaluator-poll");
            t.setDaemon(true);
            return t;
        });
        startPeriodicPoll();
    }

    public void register(String tenancyId, UUID appCaseId, String applicationId,
                          Map<String, Integer> baseReplicas) {
        String key = tenancyId + ":" + applicationId;
        registrations.put(key, new ScalingRegistration(appCaseId, applicationId,
                Map.copyOf(baseReplicas)));
    }

    public void deregister(String tenancyId, String applicationId) {
        registrations.remove(tenancyId + ":" + applicationId);
    }

    public boolean isCoolingDown(String applicationId, String serviceId) {
        String key = applicationId + ":" + serviceId;
        Instant last = lastScalingTimestamps.get(key);
        return last != null && Duration.between(last, Instant.now()).toMinutes() < 60;
    }

    boolean isCoolingDown(String applicationId, String serviceId, Duration cooldownPeriod) {
        if (cooldownPeriod == null) return false;
        String key = applicationId + ":" + serviceId;
        Instant last = lastScalingTimestamps.get(key);
        if (last == null) return false;
        return Duration.between(last, Instant.now()).compareTo(cooldownPeriod) < 0;
    }

    public void recordScalingTimestamp(String applicationId, String serviceId) {
        lastScalingTimestamps.put(applicationId + ":" + serviceId, Instant.now());
    }

    public void onSituationChange(@ObservesAsync SituationChangeEvent event) {
        evaluateForTenant(event.tenancyId());
    }

    void pollAllTenants() {
        registrations.keySet().stream()
                .map(key -> key.substring(0, key.indexOf(':')))
                .distinct()
                .forEach(this::evaluateForTenant);
    }

    private void evaluateForTenant(String tenancyId) {
        List<ActiveSituation> situations;
        try {
            situations = situationSource.activeSituations(tenancyId);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to query active situations for " + tenancyId, e);
            return;
        }

        for (var entry : registrations.entrySet()) {
            if (!entry.getKey().startsWith(tenancyId + ":")) continue;
            ScalingRegistration reg = entry.getValue();

            synchronized (reg) {
                evaluateRegistration(tenancyId, reg, situations);
            }
        }
    }

    private void evaluateRegistration(String tenancyId, ScalingRegistration reg,
                                       List<ActiveSituation> situations) {
        String servicesJson = servicesJsonLoader.apply(reg.applicationId());
        if (servicesJson == null) return;

        List<ServiceDefinition> services;
        try {
            services = ServiceDefinitionParser.parse(servicesJson, objectMapper);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse servicesJson for " + reg.applicationId(), e);
            return;
        }

        for (ServiceDefinition sd : services) {
            if (sd.scalingRules().isEmpty()) continue;

            int targetReplicas = -1;
            int mergedMinReplicas = Integer.MAX_VALUE;
            int mergedMaxReplicas = 0;
            Duration mergedCooldown = null;
            int matchCount = 0;

            for (ScalingRule rule : sd.scalingRules()) {
                var match = situations.stream()
                        .filter(s -> s.situationId().equals(rule.situationId()))
                        .filter(s -> s.confidence() >= rule.minConfidence())
                        .findFirst();
                if (match.isPresent()) {
                    int computed = rule.computeTarget(match.get().confidence());
                    if (computed > targetReplicas) {
                        targetReplicas = computed;
                    }
                    mergedMinReplicas = Math.min(mergedMinReplicas, rule.minReplicas());
                    mergedMaxReplicas = Math.max(mergedMaxReplicas, rule.maxReplicas());
                    if (rule.cooldownPeriod() != null) {
                        mergedCooldown = mergedCooldown == null ? rule.cooldownPeriod()
                                : rule.cooldownPeriod().compareTo(mergedCooldown) > 0
                                  ? rule.cooldownPeriod() : mergedCooldown;
                    }
                    matchCount++;
                }
            }

            if (matchCount == 0) {
                Integer base = reg.baseReplicas().get(sd.serviceId());
                if (base != null && base != sd.replicas()) {
                    targetReplicas = base;
                    mergedMinReplicas = 0;
                    mergedMaxReplicas = Integer.MAX_VALUE;
                } else {
                    continue;
                }
            }

            if (targetReplicas == sd.replicas()) continue;

            Duration effectiveCooldown = maxCooldownForService(sd);
            if (isCoolingDown(reg.applicationId(), sd.serviceId(), effectiveCooldown)) continue;

            ScalingPolicy policy = matchCount > 0
                    ? new ScalingPolicy(mergedMinReplicas, mergedMaxReplicas, mergedCooldown)
                    : ScalingPolicy.UNBOUNDED;

            var event = new ScalingRequestedEvent(
                    reg.appCaseId(), reg.applicationId(), tenancyId,
                    sd.serviceId(), targetReplicas, sd.replicas(),
                    matchCount > 0 ? "situation-driven" : "situation-resolved",
                    policy);

            eventSink.accept(event);
            recordScalingTimestamp(reg.applicationId(), sd.serviceId());
        }
    }

    private Duration maxCooldownForService(ServiceDefinition sd) {
        Duration max = null;
        for (ScalingRule rule : sd.scalingRules()) {
            if (rule.cooldownPeriod() != null) {
                max = max == null ? rule.cooldownPeriod()
                        : rule.cooldownPeriod().compareTo(max) > 0 ? rule.cooldownPeriod() : max;
            }
        }
        return max;
    }

    private void startPeriodicPoll() {
        pollFuture = pollScheduler.scheduleAtFixedRate(() -> {
            try {
                pollAllTenants();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Periodic scaling evaluation poll failed", e);
            }
        }, POLL_INTERVAL_MINUTES, POLL_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdown() {
        if (pollFuture != null) pollFuture.cancel(false);
        pollScheduler.shutdownNow();
    }
}
