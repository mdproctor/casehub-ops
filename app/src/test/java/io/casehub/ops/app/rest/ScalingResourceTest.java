package io.casehub.ops.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.ops.app.model.ApplicationStatus;
import io.casehub.ops.app.model.ScalingRule;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ops.app.rest.dto.ScaleServiceRequest;
import io.casehub.ops.app.service.ScalingRequestedEvent;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class ScalingResourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());

    @Test
    void validRequestReturns202() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);

        var response = resource.doScale("app-1", UUID.randomUUID(), ApplicationStatus.RUNNING,
                servicesJson("web", 2, List.of()), "web",
                new ScaleServiceRequest(5, "manual"));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).targetReplicas()).isEqualTo(5);
        assertThat(events.get(0).serviceId()).isEqualTo("web");
    }

    @Test
    void wrongStatusReturns409() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);

        var response = resource.doScale("app-1", UUID.randomUUID(), ApplicationStatus.DRAFT,
                servicesJson("web", 2, List.of()), "web",
                new ScaleServiceRequest(5, "manual"));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(events).isEmpty();
    }

    @Test
    void nullEngineCaseIdReturns409() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);

        var response = resource.doScale("app-1", null, ApplicationStatus.RUNNING,
                servicesJson("web", 2, List.of()), "web",
                new ScaleServiceRequest(5, "manual"));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void unknownServiceReturns404() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);

        var response = resource.doScale("app-1", UUID.randomUUID(), ApplicationStatus.RUNNING,
                servicesJson("web", 2, List.of()), "nonexistent",
                new ScaleServiceRequest(5, "manual"));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void coolingDownReturns429() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        Set<String> coolingDown = new HashSet<>();
        coolingDown.add("app-1:web");
        var resource = buildResourceWithCooldown(events, coolingDown);

        var response = resource.doScale("app-1", UUID.randomUUID(), ApplicationStatus.RUNNING,
                servicesJson("web", 2, List.of(new ScalingRule("x", 0.5, 2, 10, Duration.ofMinutes(5)))),
                "web", new ScaleServiceRequest(5, "manual"));

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void serviceWithRulesIncludesWarningHeader() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);

        var response = resource.doScale("app-1", UUID.randomUUID(), ApplicationStatus.RUNNING,
                servicesJson("web", 2, List.of(new ScalingRule("x", 0.5, 2, 10, null))),
                "web", new ScaleServiceRequest(5, "manual"));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeaderString("X-Scaling-Warning")).isEqualTo("active-rules");
    }

    @Test
    void serviceWithoutRulesNoWarningHeader() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);

        var response = resource.doScale("app-1", UUID.randomUUID(), ApplicationStatus.RUNNING,
                servicesJson("web", 2, List.of()), "web",
                new ScaleServiceRequest(5, "manual"));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeaderString("X-Scaling-Warning")).isNull();
    }

    @Test
    void degradedStatusAllowed() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);

        var response = resource.doScale("app-1", UUID.randomUUID(), ApplicationStatus.DEGRADED,
                servicesJson("web", 2, List.of()), "web",
                new ScaleServiceRequest(5, "manual"));

        assertThat(response.getStatus()).isEqualTo(202);
    }

    @Test
    void nullReasonDefaultsToManual() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);

        resource.doScale("app-1", UUID.randomUUID(), ApplicationStatus.RUNNING,
                servicesJson("web", 2, List.of()), "web",
                new ScaleServiceRequest(5, null));

        assertThat(events.get(0).reason()).isEqualTo("manual");
    }

    // --- helpers ---

    private ScalingResource buildResource(List<ScalingRequestedEvent> events) {
        return buildResourceWithCooldown(events, Set.of());
    }

    private ScalingResource buildResourceWithCooldown(List<ScalingRequestedEvent> events,
                                                       Set<String> coolingDown) {
        return new ScalingResource(events::add,
                (appId, serviceId) -> coolingDown.contains(appId + ":" + serviceId),
                (appId, serviceId) -> {},
                objectMapper);
    }

    private String servicesJson(String serviceId, int replicas, List<ScalingRule> rules) {
        var sd = new ServiceDefinition(serviceId, serviceId, "img:1.0", replicas,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of(), Optional.empty(), List.of(), rules);
        try {
            return objectMapper.writeValueAsString(List.of(sd));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
