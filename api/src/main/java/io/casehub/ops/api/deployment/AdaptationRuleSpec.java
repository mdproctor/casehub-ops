package io.casehub.ops.api.deployment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdaptationRuleSpec(
        String name,
        AdaptationTrigger trigger,
        List<AdaptationActionSpec> actions
) {
    public AdaptationRuleSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(actions, "actions");
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("actions must not be empty");
        }
        actions = List.copyOf(actions);
    }
}
