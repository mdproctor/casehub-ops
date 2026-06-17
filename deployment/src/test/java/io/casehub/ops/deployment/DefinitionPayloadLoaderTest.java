package io.casehub.ops.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DefinitionPayloadLoaderTest {

    private DefinitionPayloadLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DefinitionPayloadLoader();
    }

    @Test
    void loadsFromClasspath() {
        Map<String, Object> payload = loader.load("test-case-defs/pr-review.yaml");
        assertThat(payload).containsEntry("namespace", "io.casehub.devtown");
        assertThat(payload).containsEntry("name", "pr-review");
        assertThat(payload).containsEntry("version", "1.0");
        assertThat(payload).containsKey("capabilities");
    }

    @Test
    void preservesNullValues() {
        Map<String, Object> payload = loader.load("test-case-defs/pr-review.yaml");
        assertThat(payload).containsKey("optionalField");
        assertThat(payload.get("optionalField")).isNull();
    }

    @Test
    void resultMapIsImmutable() {
        Map<String, Object> payload = loader.load("test-case-defs/pr-review.yaml");
        assertThatThrownBy(() -> payload.put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedListIsImmutable() {
        Map<String, Object> payload = loader.load("test-case-defs/pr-review.yaml");
        List<Object> capabilities = (List<Object>) payload.get("capabilities");
        assertThatThrownBy(() -> capabilities.add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void loadsFromFilesystem(@TempDir Path tempDir) throws IOException {
        Path yamlFile = tempDir.resolve("test.yaml");
        Files.writeString(yamlFile, "namespace: test\nname: from-file\nversion: '1.0'\n");
        Map<String, Object> payload = loader.load(yamlFile.toString());
        assertThat(payload).containsEntry("namespace", "test");
        assertThat(payload).containsEntry("name", "from-file");
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> loader.load("nonexistent/path.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }
}
