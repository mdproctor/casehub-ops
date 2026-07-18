package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.ops.app.model.ScalingRule;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationChangeEvent.ChangeType;
import io.casehub.ras.api.SituationContext;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class SituationScalingEvaluatorTest {

    private List<ScalingRequestedEvent> firedEvents;
    private List<ActiveSituation> activeSituations;
    private String currentServicesJson;
    private SituationScalingEvaluator evaluator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        firedEvents = new CopyOnWriteArrayList<>();
        activeSituations = new CopyOnWriteArrayList<>();
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module());

        evaluator = new SituationScalingEvaluator(
                tenancyId -> Uni.createFrom().item(List.copyOf(activeSituations)),
                firedEvents::add,
                appId -> currentServicesJson,
                objectMapper);
    }

    @Test
    void situationMatchingRuleFiresEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).serviceId()).isEqualTo("web");
        // effective = (0.8 - 0.5) / (1.0 - 0.5) = 0.6, target = 2 + (int)(8 * 0.6) = 6
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(6);
    }

    @Test
    void situationBelowMinConfidenceNoEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.7, 2, 10);
        activeSituations.add(situation("high-load", "tenant-1", 0.5));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).isEmpty();
    }

    @Test
    void noRegisteredAppsNoEvent() {
        activeSituations.add(situation("high-load", "tenant-1", 0.8));
        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void serviceWithNoRulesSkipped() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesNoRules("web", 2);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).isEmpty();
    }

    @Test
    void targetEqualsCurrentNoEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.0, 2, 2);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).isEmpty();
    }

    @Test
    void multipleRulesMatchMaxWins() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        var rules = List.of(
                new ScalingRule("high-load", 0.5, 2, 8, null),
                new ScalingRule("peak-hours", 0.3, 2, 12, null));
        setServicesWithRules("web", 2, rules);
        activeSituations.add(situation("high-load", "tenant-1", 1.0));
        activeSituations.add(situation("peak-hours", "tenant-1", 1.0));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(12);
    }

    @Test
    void multipleRulesMergedPolicyUsesMinMinMaxMax() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        var rules = List.of(
                new ScalingRule("high-load", 0.5, 3, 8, Duration.ofMinutes(2)),
                new ScalingRule("peak-hours", 0.3, 1, 12, Duration.ofMinutes(5)));
        setServicesWithRules("web", 2, rules);
        activeSituations.add(situation("high-load", "tenant-1", 1.0));
        activeSituations.add(situation("peak-hours", "tenant-1", 1.0));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).hasSize(1);
        var policy = firedEvents.get(0).policy();
        assertThat(policy.minReplicas()).isEqualTo(1);
        assertThat(policy.maxReplicas()).isEqualTo(12);
        assertThat(policy.cooldownPeriod()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void resolvedSituationScalesDown() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 8, "high-load", 0.5, 2, 10);

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.RESOLVED));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(2);
    }

    @Test
    void discardedSameAsResolved() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 8, "high-load", 0.5, 2, 10);

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.DISCARDED));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(2);
    }

    @Test
    void cooldownSuppressesEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10, Duration.ofMinutes(5));
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(1);

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void afterCooldownExpiresEventFires() throws InterruptedException {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10, Duration.ofMillis(50));
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(1);

        Thread.sleep(100);

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(2);
    }

    @Test
    void deregisteredAppNoLongerEvaluated() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.deregister("tenant-1", "app-1");

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void isCoolingDownReturnsTrueAfterEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10, Duration.ofMinutes(5));
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(evaluator.isCoolingDown("app-1", "web")).isTrue();
    }

    @Test
    void recordScalingTimestampEnablesCooldownForRestPath() {
        evaluator.recordScalingTimestamp("app-1", "web");
        assertThat(evaluator.isCoolingDown("app-1", "web")).isTrue();
    }

    @Test
    void multipleServicesIndependentEvents() {
        registerApp("tenant-1", "app-1", Map.of("web", 2, "api", 3));
        var webSd = new ServiceDefinition("web", "web", "img:1.0", 2,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of(), Optional.empty(), List.of(),
                List.of(new ScalingRule("high-load", 0.5, 2, 10, null)));
        var apiSd = new ServiceDefinition("api", "api", "img:1.0", 3,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of(), Optional.empty(), List.of(),
                List.of(new ScalingRule("high-load", 0.5, 3, 8, null)));
        try {
            currentServicesJson = objectMapper.writeValueAsString(List.of(webSd, apiSd));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        activeSituations.add(situation("high-load", "tenant-1", 1.0));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).hasSize(2);
        assertThat(firedEvents).extracting(ScalingRequestedEvent::serviceId)
                .containsExactlyInAnyOrder("web", "api");
    }

    @Test
    void onlyMatchingRuleFires() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        var rules = List.of(
                new ScalingRule("high-load", 0.5, 2, 10, null),
                new ScalingRule("peak-hours", 0.3, 2, 6, null));
        setServicesWithRules("web", 2, rules);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).hasSize(1);
        // Only high-load matches, peak-hours not in activeSituations
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(6);
    }

    @Test
    void pollAllTenantsDetectsMissedEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.pollAllTenants();

        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void pollDuringCooldownNoEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10, Duration.ofMinutes(5));
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(1);

        evaluator.pollAllTenants();
        assertThat(firedEvents).hasSize(1);
    }

    // --- helpers ---

    private void registerApp(String tenancyId, String appId, Map<String, Integer> baseReplicas) {
        UUID caseId = UUID.randomUUID();
        evaluator.register(tenancyId, caseId, appId, baseReplicas);
    }

    private void setServicesWithRule(String serviceId, int replicas,
                                     String situationId, double minConf,
                                     int minRep, int maxRep) {
        setServicesWithRule(serviceId, replicas, situationId, minConf, minRep, maxRep, null);
    }

    private void setServicesWithRule(String serviceId, int replicas,
                                     String situationId, double minConf,
                                     int minRep, int maxRep, Duration cooldown) {
        setServicesWithRules(serviceId, replicas,
                List.of(new ScalingRule(situationId, minConf, minRep, maxRep, cooldown)));
    }

    private void setServicesWithRules(String serviceId, int replicas, List<ScalingRule> rules) {
        var sd = new ServiceDefinition(serviceId, serviceId, "img:1.0", replicas,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of(), Optional.empty(), List.of(), rules);
        try {
            currentServicesJson = objectMapper.writeValueAsString(List.of(sd));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setServicesNoRules(String serviceId, int replicas) {
        setServicesWithRules(serviceId, replicas, List.of());
    }

    private ActiveSituation situation(String situationId, String tenancyId, double confidence) {
        return new ActiveSituation(situationId, "corr-1", tenancyId, confidence,
                Map.of(), Instant.now(), Instant.now(), 1);
    }

    private SituationChangeEvent event(String tenancyId, String situationId, ChangeType type) {
        return new SituationChangeEvent(tenancyId, situationId, "corr-1", type,
                SituationContext.initial(situationId, "corr-1", tenancyId, Instant.now()));
    }
}
