package io.casehub.ops.service.api;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.ops.api.deployment.AdaptationSnapshotProvider;
import io.casehub.ops.api.deployment.AdaptationSnapshotProvider.AdaptationSnapshot;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@McpDomain(value = "ops/topology", app = "ops", basePath = "/api/ops/topology",
        summary = "Topology observation — desired/actual state and adaptation status")
@ApplicationScoped
public class OpsTopologyApi {

    @Inject ReconciliationLoop reconciliationLoop;
    @Inject ActualStateAdapterRouter actualStateRouter;
    @Inject
    jakarta.enterprise.inject.Instance<AdaptationSnapshotProvider> adaptationProvider;

    @PlatformQuery("Get current topology snapshot — desired nodes, actual statuses, adaptation state")
    @RestPath("/")
    public TopologySnapshot getTopology(@ContextParam("tenancyId") String tenancyId) {
        String prefix = tenancyId + ":";
        List<LoopTopology> loops = new ArrayList<>();

        for (String key : reconciliationLoop.tenantIds()) {
            if (!key.startsWith(prefix)) {
                continue;
            }

            DesiredStateGraph desired = reconciliationLoop.getDesired(key);
            if (desired == null) {
                continue;
            }

            List<TopologyNode> desiredNodes = new ArrayList<>();
            for (var entry : desired.nodes().entrySet()) {
                NodeId nodeId = entry.getKey();
                DesiredNode node = entry.getValue();
                boolean adapted = nodeId.value().contains("~");
                desiredNodes.add(new TopologyNode(
                        nodeId.value(),
                        node.type().value(),
                        node.targetStatus().name(),
                        adapted));
            }

            ActualState actual = actualStateRouter.readActual(desired, tenancyId);
            List<NodeStatusEntry> actualStatuses = new ArrayList<>();
            for (var entry : actual.statuses().entrySet()) {
                actualStatuses.add(new NodeStatusEntry(
                        entry.getKey().value(),
                        entry.getValue().name()));
            }

            loops.add(new LoopTopology(key, desiredNodes, actualStatuses));
        }

        TopologyAdaptation topologyAdaptation;
        if (adaptationProvider.isResolvable()) {
            AdaptationSnapshot adaptation = adaptationProvider.get().getAdaptationSnapshot(tenancyId);
            topologyAdaptation = new TopologyAdaptation(
                    adaptation.activeRules().stream()
                            .map(r -> new AdaptationRuleStatus(r.ruleName(), r.situationId(), r.active()))
                            .toList(),
                    adaptation.trackedSituations().stream()
                            .map(s -> new SituationStatus(s.situationId(), s.confidence(), s.since(), s.lastSignal()))
                            .toList());
        } else {
            topologyAdaptation = new TopologyAdaptation(List.of(), List.of());
        }

        return new TopologySnapshot(loops, topologyAdaptation);
    }

    public record TopologySnapshot(List<LoopTopology> loops, TopologyAdaptation adaptation) {}

    public record LoopTopology(String loopKey, List<TopologyNode> desiredNodes,
                               List<NodeStatusEntry> actualStatuses) {}

    public record TopologyNode(String nodeId, String nodeType, String targetStatus, boolean adapted) {}

    public record NodeStatusEntry(String nodeId, String status) {}

    public record TopologyAdaptation(List<AdaptationRuleStatus> activeRules,
                                     List<SituationStatus> trackedSituations) {}

    public record AdaptationRuleStatus(String ruleName, String situationId, boolean active) {}

    public record SituationStatus(String situationId, double confidence,
                                  Instant since, Instant lastSignal) {}
}
