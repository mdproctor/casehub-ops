package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigHashEvidenceCollectorTest {

    private ConfigHashEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ConfigHashEvidenceCollector();
    }

    @Test
    void strategyIsConfigHash() {
        assertThat(collector.strategy()).isEqualTo("CONFIG_HASH");
    }

    @Test
    void missingFilePathProperty_returnsUnavailable() {
        var spec = spec("ctrl-1", Map.of("expectedHash", "abc123"));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("filePath");
    }

    @Test
    void missingExpectedHashProperty_returnsUnavailable(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("config.yaml"), "key: value");
        var spec = spec("ctrl-2", Map.of("filePath", file.toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("expectedHash");
    }

    @Test
    void fileNotFound_returnsUnavailable(@TempDir Path tempDir) {
        var spec = spec("ctrl-3", Map.of(
                "filePath", tempDir.resolve("nonexistent.yaml").toString(),
                "expectedHash", "abc123"));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("not found");
    }

    @Test
    void hashMatch_returnsPass(@TempDir Path tempDir) throws Exception {
        String content = "key: value\n";
        Path file = Files.writeString(tempDir.resolve("config.yaml"), content);
        String hash = sha256Hex(content);
        var spec = spec("ctrl-4", Map.of("filePath", file.toString(), "expectedHash", hash));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Pass.class);
    }

    @Test
    void hashMismatch_returnsFail(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("config.yaml"), "key: value\n");
        var spec = spec("ctrl-5", Map.of("filePath", file.toString(), "expectedHash", "wrong"));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("mismatch");
    }

    @Test
    void customAlgorithm_works(@TempDir Path tempDir) throws Exception {
        String content = "data";
        Path file = Files.writeString(tempDir.resolve("config.yaml"), content);
        String md5Hash = hexHash(content, "MD5");
        var spec = spec("ctrl-6", Map.of(
                "filePath", file.toString(),
                "expectedHash", md5Hash,
                "algorithm", "MD5"));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Pass.class);
    }

    @Test
    void invalidAlgorithm_returnsUnavailable(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("config.yaml"), "data");
        var spec = spec("ctrl-7", Map.of(
                "filePath", file.toString(),
                "expectedHash", "abc",
                "algorithm", "BOGUS-256"));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("algorithm");
    }

    private ComplianceControlSpec spec(String id, Map<String, Object> props) {
        return new ComplianceControlSpec(
                id, "CONFIG_HASH", "CONFIG_HASH", "T", "D", List.of(),
                7, false, props);
    }

    private String sha256Hex(String content) throws Exception {
        return hexHash(content, "SHA-256");
    }

    private String hexHash(String content, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
