package io.casehub.ops.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ops.app.case_.ScalingPolicy;
import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.model.ApplicationStatus;
import io.casehub.ops.app.model.ScalingRule;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ops.app.rest.dto.ScaleServiceRequest;
import io.casehub.ops.app.service.ScalingRequestedEvent;
import io.casehub.ops.app.service.ServiceDefinitionParser;
import io.casehub.ops.app.service.SituationScalingEvaluator;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

@Blocking
@ApplicationScoped
@Path("/api/applications/{appId}/services/{serviceId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScalingResource {

    private final Consumer<ScalingRequestedEvent> eventSink;
    private final BiPredicate<String, String> cooldownChecker;
    private final BiConsumer<String, String> timestampRecorder;
    private final ObjectMapper objectMapper;

    @Inject
    public ScalingResource(Event<ScalingRequestedEvent> cdiEvent,
                           SituationScalingEvaluator evaluator,
                           ObjectMapper objectMapper) {
        this(event -> cdiEvent.fireAsync(event),
             evaluator::isCoolingDown,
             evaluator::recordScalingTimestamp,
             objectMapper);
    }

    ScalingResource(Consumer<ScalingRequestedEvent> eventSink,
                    BiPredicate<String, String> cooldownChecker,
                    BiConsumer<String, String> timestampRecorder,
                    ObjectMapper objectMapper) {
        this.eventSink = eventSink;
        this.cooldownChecker = cooldownChecker;
        this.timestampRecorder = timestampRecorder;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/scale")
    public Response scale(@PathParam("appId") UUID appId,
                          @PathParam("serviceId") String serviceId,
                          ScaleServiceRequest request) {
        var app = ApplicationEntity.<ApplicationEntity>findById(appId);
        if (app == null) return Response.status(Response.Status.NOT_FOUND).build();

        return doScale(app.id.toString(), app.engineCaseId, app.status, app.servicesJson,
                       serviceId, request);
    }

    Response doScale(String applicationId, UUID engineCaseId, ApplicationStatus status,
                     String servicesJson, String serviceId, ScaleServiceRequest request) {
        if (status != ApplicationStatus.RUNNING && status != ApplicationStatus.DEGRADED) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Application status must be RUNNING or DEGRADED")).build();
        }

        if (engineCaseId == null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "No active case for application")).build();
        }

        List<ServiceDefinition> services = ServiceDefinitionParser.parse(servicesJson, objectMapper);
        ServiceDefinition target = services.stream()
                .filter(sd -> sd.serviceId().equals(serviceId))
                .findFirst().orElse(null);

        if (target == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Service not found: " + serviceId)).build();
        }

        Duration maxCooldown = maxCooldownForService(target);
        if (maxCooldown != null && cooldownChecker.test(applicationId, serviceId)) {
            return Response.status(429)
                    .entity(Map.of("error", "Service is cooling down")).build();
        }

        ScalingPolicy policy = target.scalingRules().isEmpty()
                ? ScalingPolicy.UNBOUNDED
                : mergedPolicy(target.scalingRules());

        var event = new ScalingRequestedEvent(
                engineCaseId, applicationId, "",
                serviceId, request.targetReplicas(), target.replicas(),
                request.reason() != null ? request.reason() : "manual",
                policy);

        eventSink.accept(event);
        timestampRecorder.accept(applicationId, serviceId);

        var responseBuilder = Response.accepted();
        if (!target.scalingRules().isEmpty()) {
            responseBuilder.header("X-Scaling-Warning", "active-rules");
            responseBuilder.entity(Map.of(
                    "status", "accepted",
                    "warning", "This service has automatic scaling rules — manual scaling will be overridden when situations change."));
        } else {
            responseBuilder.entity(Map.of("status", "accepted"));
        }
        return responseBuilder.build();
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

    private ScalingPolicy mergedPolicy(List<ScalingRule> rules) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        Duration cooldown = null;
        for (ScalingRule r : rules) {
            min = Math.min(min, r.minReplicas());
            max = Math.max(max, r.maxReplicas());
            if (r.cooldownPeriod() != null) {
                cooldown = cooldown == null ? r.cooldownPeriod()
                        : r.cooldownPeriod().compareTo(cooldown) > 0 ? r.cooldownPeriod() : cooldown;
            }
        }
        return new ScalingPolicy(min, max, cooldown);
    }
}
