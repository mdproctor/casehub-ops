package io.casehub.ops.compliance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class ComplianceGoalLoaderTest {

    private ComplianceGoalLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ComplianceGoalLoader();
    }

    @Test
    void loadsSingleFile() {
        var goals = loader.load("test-compliance/encryption-only.yaml");
        assertThat(goals.controls()).hasSize(1);
        assertThat(goals.controls().get(0).spec().controlId()).isEqualTo("encryption-at-rest");
        assertThat(goals.controls().get(0).spec().controlType()).isEqualTo("ENCRYPTION_AT_REST");
        assertThat(goals.controls().get(0).spec().frameworks()).hasSize(2);
    }

    @Test
    void loadsAllControls() {
        var goals = loader.load("test-compliance/all-controls.yaml");
        assertThat(goals.controls()).hasSize(8);
    }

    @Test
    void loadsDirectoryAndMerges(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("encryption.yaml"),
                "controls:\n  - spec:\n      controlId: enc\n      controlType: ENCRYPTION_AT_REST\n      strategy: FILE_EXISTENCE\n      title: Enc\n      description: D\n      evidenceMaxAgeDays: 30\n      requiresHumanReview: false\n");
        Files.writeString(tempDir.resolve("logging.yaml"),
                "controls:\n  - spec:\n      controlId: log\n      controlType: LOG_RETENTION\n      strategy: LOG_DIRECTORY\n      title: Log\n      description: D\n      evidenceMaxAgeDays: 90\n      requiresHumanReview: false\n");

        var goals = loader.loadDirectory(tempDir.toString());
        assertThat(goals.controls()).hasSize(2);
    }

    @Test
    void mergesConcatenatesLists() {
        var goals1 = loader.load("test-compliance/encryption-only.yaml");
        var goals2 = loader.load("test-compliance/encryption-only.yaml");
        var merged = loader.merge(goals1, goals2);
        assertThat(merged.controls()).hasSize(2);
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> loader.load("nonexistent.yaml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notADirectoryThrows(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("not-a-dir.yaml");
        Files.writeString(file, "controls: []");
        assertThatThrownBy(() -> loader.loadDirectory(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a directory");
    }
}
