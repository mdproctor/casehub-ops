package io.casehub.ops.api.deployment;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.path.Path;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EndpointNodeSpec(
        String path,
        EndpointType type,
        EndpointProtocol protocol,
        Map<String, String> properties,
        String credentialRef,
        Set<EndpointCapability> capabilities
) implements DeploymentNodeSpec {

    public EndpointNodeSpec {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(protocol, "protocol is required");

        properties = properties != null ? Map.copyOf(properties) : Map.of();
        capabilities = capabilities != null ? Set.copyOf(capabilities) : Set.of();

        // Protocol-specific validation
        if (protocol == EndpointProtocol.KAFKA) {
            requireProperty(properties, EndpointPropertyKeys.TOPIC, "KAFKA");
        } else if (protocol == EndpointProtocol.HTTP || protocol == EndpointProtocol.GRPC) {
            requireProperty(properties, EndpointPropertyKeys.URL, "HTTP/GRPC");
        }
    }

    @Override
    public String nodeId() {
        return path;
    }

    @Override
    public String nodeType() {
        return "endpoint";
    }

    public EndpointDescriptor toDescriptor(String tenancyId) {
        return new EndpointDescriptor(
                Path.parse(path),
                tenancyId,
                type,
                protocol,
                properties,
                credentialRef,
                capabilities
        );
    }

    private static void requireProperty(Map<String, String> properties, String key, String protocolName) {
        if (!properties.containsKey(key) || properties.get(key) == null || properties.get(key).isBlank()) {
            throw new IllegalArgumentException(protocolName + " endpoints require property: " + key);
        }
    }
}
