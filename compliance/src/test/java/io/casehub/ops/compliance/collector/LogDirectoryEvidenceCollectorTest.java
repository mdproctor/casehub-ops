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

class LogDirectoryEvidenceCollectorTest {

    private LogDirectoryEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        collector = new LogDirectoryEvidenceCollector();
    }

    @Test
    void strategyIsLogDirectory() {
        assertThat(collector.strategy()).isEqualTo("LOG_DIRECTORY");
    }

    @Test
    void missingLogDirectoryProperty_returnsUnavailable() {
        var spec = spec("ctrl-1", Map.of("retentionDays", 365));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("logDirectory");
    }

    @Test
    void missingRetentionDaysProperty_returnsUnavailable(@TempDir Path tempDir) {
        var spec = spec("ctrl-2", Map.of("logDirectory", tempDir.toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("retentionDays");
    }

    @Test
    void directoryNotFound_returnsFail(@TempDir Path tempDir) {
        var spec = spec("ctrl-3", Map.of(
                "logDirectory", tempDir.resolve("nonexistent").toString(),
                "retentionDays", 365));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("not found");
    }

    @Test
    void emptyDirectory_returnsFail(@TempDir Path tempDir) throws IOException {
        Path logDir = Files.createDirectory(tempDir.resolve("logs"));
        var spec = spec("ctrl-4", Map.of(
                "logDirectory", logDir.toString(),
                "retentionDays", 365));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("empty");
    }

    @Test
    void noRecentFiles_returnsFail(@TempDir Path tempDir) throws IOException {
        Path logDir = Files.createDirectory(tempDir.resolve("logs"));
        Path oldFile = Files.writeString(logDir.resolve("old.log"), "old data");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(400, ChronoUnit.DAYS)));
        var spec = specWithMaxAge("ctrl-5", logDir.toString(), 365, 30);
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("recent");
    }

    @Test
    void noHistoricalRetention_returnsFail(@TempDir Path tempDir) throws IOException {
        Path logDir = Files.createDirectory(tempDir.resolve("logs"));
        Files.writeString(logDir.resolve("today.log"), "recent data");
        var spec = spec("ctrl-6", Map.of(
                "logDirectory", logDir.toString(),
                "retentionDays", 365));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("retention");
    }

    @Test
    void recentFilesAndRetentionSatisfied_returnsPass(@TempDir Path tempDir) throws IOException {
        Path logDir = Files.createDirectory(tempDir.resolve("logs"));
        Files.writeString(logDir.resolve("today.log"), "recent data");
        Path oldFile = Files.writeString(logDir.resolve("archive.log"), "old data");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(400, ChronoUnit.DAYS)));
        var spec = spec("ctrl-7", Map.of(
                "logDirectory", logDir.toString(),
                "retentionDays", 365));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Pass.class);
    }

    private ComplianceControlSpec spec(String id, Map<String, Object> props) {
        return new ComplianceControlSpec(
                id, "LOG_RETENTION", "LOG_DIRECTORY", "T", "D", List.of(),
                90, false, props);
    }

    private ComplianceControlSpec specWithMaxAge(String id, String logDir, int retentionDays, int evidenceMaxAgeDays) {
        return new ComplianceControlSpec(
                id, "LOG_RETENTION", "LOG_DIRECTORY", "T", "D", List.of(),
                evidenceMaxAgeDays, false,
                Map.of("logDirectory", logDir, "retentionDays", retentionDays));
    }
}
