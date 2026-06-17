package io.casehub.ops.api.deployment;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ProviderConfigTest {

    @Test
    void validConstruction() {
        var config = new ProviderConfig("claudony", Map.of("tools", "read,write"));
        assertThat(config.providerName()).isEqualTo("claudony");
        assertThat(config.config()).containsEntry("tools", "read,write");
    }

    @Test
    void nullProviderNameRejected() {
        assertThatThrownBy(() -> new ProviderConfig(null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName is required");
    }

    @Test
    void blankProviderNameRejected() {
        assertThatThrownBy(() -> new ProviderConfig("  ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName is required");
    }

    @Test
    void nullConfigDefaultsToEmpty() {
        var config = new ProviderConfig("claudony", null);
        assertThat(config.config()).isEmpty();
    }

    @Test
    void configIsImmutable() {
        var config = new ProviderConfig("claudony", Map.of("key", "value"));
        assertThatThrownBy(() -> config.config().put("new", "entry"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullValuesPreservedInConfig() {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("systemPrompt", "prompts/reviewer.md");
        input.put("optionalField", null);
        var config = new ProviderConfig("claudony", input);
        assertThat(config.config()).containsEntry("optionalField", null);
    }
}
