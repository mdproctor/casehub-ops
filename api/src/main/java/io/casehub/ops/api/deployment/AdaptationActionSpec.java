package io.casehub.ops.api.deployment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AdaptationActionSpec.ScaleActionSpec.class, name = "scale"),
    @JsonSubTypes.Type(value = AdaptationActionSpec.AddActionSpec.class, name = "add"),
    @JsonSubTypes.Type(value = AdaptationActionSpec.UpdateActionSpec.class, name = "update")
})
public sealed interface AdaptationActionSpec {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScaleActionSpec(String target, int min, int max) implements AdaptationActionSpec {
        public ScaleActionSpec {
            Objects.requireNonNull(target, "target");
            if (target.contains("~")) {
                throw new IllegalArgumentException(
                    "scale target must not contain '~': " + target);
            }
            if (min < 1) {
                throw new IllegalArgumentException("min must be >= 1, got: " + min);
            }
            if (max < min) {
                throw new IllegalArgumentException(
                    "max must be >= min, got min=" + min + " max=" + max);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddActionSpec(DeploymentGoals nodes) implements AdaptationActionSpec {
        public AddActionSpec {
            Objects.requireNonNull(nodes, "nodes");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpdateActionSpec(
            String target,
            String nodeType,
            Map<String, Object> fields
    ) implements AdaptationActionSpec {
        public UpdateActionSpec {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(fields, "fields");
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("fields must not be empty");
            }
            fields = Map.copyOf(fields);
        }
    }
}
