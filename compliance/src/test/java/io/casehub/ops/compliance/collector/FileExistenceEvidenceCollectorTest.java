package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileExistenceEvidenceCollectorTest {

    private FileExistenceEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        collector = new FileExistenceEvidenceCollector();
    }

    @Test
    void strategyIsFileExistence() {
        assertThat(collector.strategy()).isEqualTo("FILE_EXISTENCE");
    }

    @Test
    void missingFilePathProperty_returnsUnavailable() {
        var spec = spec("ctrl-1", Map.of());
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("filePath");
    }

    @Test
    void fileNotFound_returnsFail(@TempDir Path tempDir) {
        var spec = spec("ctrl-2", Map.of("filePath", tempDir.resolve("nonexistent.txt").toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("not found");
    }

    @Test
    void freshFile_returnsPass(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("review.pdf"), "content");
        var spec = spec("ctrl-3", Map.of("filePath", file.toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Pass.class);
    }

    @Test
    void staleFile_returnsFail(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("old-review.pdf"), "content");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));
        var spec = specWithMaxAge("ctrl-4", file.toString(), 30);
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("stale");
    }

    @Test
    void maxAgeDaysFromProperties_overridesSpec(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("review.pdf"), "content");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));
        var spec = new ComplianceControlSpec(
                "ctrl-5", "ACCESS_REVIEW", "FILE_EXISTENCE", "T", "D", List.of(),
                90, false, Map.of("filePath", file.toString(), "maxAgeDays", 5));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
    }

    @Test
    void unreadableFile_returnsUnavailable(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("secret.pdf"), "content");
        file.toFile().setReadable(false);
        var spec = spec("ctrl-6", Map.of("filePath", file.toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        file.toFile().setReadable(true);
    }

    private ComplianceControlSpec spec(String id, Map<String, Object> props) {
        return new ComplianceControlSpec(
                id, "ACCESS_REVIEW", "FILE_EXISTENCE", "T", "D", List.of(),
                30, false, props);
    }

    private ComplianceControlSpec specWithMaxAge(String id, String filePath, int maxAgeDays) {
        return new ComplianceControlSpec(
                id, "ACCESS_REVIEW", "FILE_EXISTENCE", "T", "D", List.of(),
                maxAgeDays, false, Map.of("filePath", filePath));
    }
}
