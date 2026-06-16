package io.casehub.ops.api.deployment;

import java.util.List;
import java.util.Map;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;

public record AgentNodeSpec(
        String agentId,
        String name,
        String slot,
        String provider,
        String modelFamily,
        String modelVersion,
        String version,
        String weightsFingerprint,
        String domainVocabulary,
        String slotVocabulary,
        String dispositionVocabulary,
        Map<DispositionAxis, String> axisVocabularies,
        List<AgentCapability> capabilities,
        AgentDisposition disposition,
        String jurisdiction,
        String dataHandlingPolicy
) implements DeploymentNodeSpec {

    public AgentNodeSpec {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("slot is required");
        }
        capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        axisVocabularies = axisVocabularies != null ? Map.copyOf(axisVocabularies) : null;
    }

    @Override
    public String nodeId() {
        return agentId;
    }

    @Override
    public String nodeType() {
        return "agent";
    }
}
