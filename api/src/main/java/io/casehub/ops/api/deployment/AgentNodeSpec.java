package io.casehub.ops.api.deployment;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;

@JsonIgnoreProperties(ignoreUnknown = true)
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
        String dataHandlingPolicy,
        String briefing,
        List<ProviderConfig> providerConfigs
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
        providerConfigs = providerConfigs != null ? List.copyOf(providerConfigs) : List.of();
    }

    @Override
    public String nodeId() {
        return agentId;
    }

    @Override
    public String nodeType() {
        return "agent";
    }

    public AgentDescriptor toDescriptor(String tenancyId) {
        return new AgentDescriptor(
                agentId, name, version, provider, modelFamily, modelVersion,
                weightsFingerprint, domainVocabulary, slotVocabulary,
                dispositionVocabulary, axisVocabularies, slot, capabilities,
                disposition, jurisdiction, dataHandlingPolicy, tenancyId, briefing);
    }
}
