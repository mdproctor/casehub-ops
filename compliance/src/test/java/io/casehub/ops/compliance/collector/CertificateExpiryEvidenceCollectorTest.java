package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CertificateExpiryEvidenceCollectorTest {

    private CertificateExpiryEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        collector = new CertificateExpiryEvidenceCollector();
    }

    @Test
    void strategyIsCertificateExpiry() {
        assertThat(collector.strategy()).isEqualTo("CERTIFICATE_EXPIRY");
    }

    @Test
    void missingCertPathProperty_returnsUnavailable() {
        var spec = spec("ctrl-1", Map.of());
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("certPath");
    }

    @Test
    void certFileNotFound_returnsUnavailable(@TempDir Path tempDir) {
        var spec = spec("ctrl-2", Map.of("certPath", tempDir.resolve("missing.pem").toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
        assertThat(result.detail()).contains("not found");
    }

    @Test
    void validCert_returnsPass(@TempDir Path tempDir) throws Exception {
        Path certFile = generateCert(tempDir, "valid.pem", 365);
        var spec = spec("ctrl-3", Map.of("certPath", certFile.toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Pass.class);
    }

    @Test
    void expiredCert_returnsFail(@TempDir Path tempDir) throws Exception {
        Path certFile = generateCert(tempDir, "expired.pem", -1);
        var spec = spec("ctrl-4", Map.of("certPath", certFile.toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("expired");
    }

    @Test
    void nearExpiryCert_returnsFail(@TempDir Path tempDir) throws Exception {
        Path certFile = generateCert(tempDir, "near-expiry.pem", 15);
        var spec = spec("ctrl-5", Map.of(
                "certPath", certFile.toString(), "warningThresholdDays", 30));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Fail.class);
        assertThat(result.detail()).contains("within warning threshold");
    }

    @Test
    void customWarningThreshold_respected(@TempDir Path tempDir) throws Exception {
        Path certFile = generateCert(tempDir, "threshold.pem", 15);
        var specSmall = spec("ctrl-6a", Map.of(
                "certPath", certFile.toString(), "warningThresholdDays", 10));
        assertThat(collector.collect(specSmall, "t")).isInstanceOf(EvidenceResult.Pass.class);

        var specLarge = spec("ctrl-6b", Map.of(
                "certPath", certFile.toString(), "warningThresholdDays", 20));
        assertThat(collector.collect(specLarge, "t")).isInstanceOf(EvidenceResult.Fail.class);
    }

    @Test
    void unparseableFile_returnsUnavailable(@TempDir Path tempDir) throws IOException {
        Path file = Files.writeString(tempDir.resolve("garbage.pem"), "not a certificate");
        var spec = spec("ctrl-7", Map.of("certPath", file.toString()));
        EvidenceResult result = collector.collect(spec, "tenant-1");
        assertThat(result).isInstanceOf(EvidenceResult.Unavailable.class);
    }

    private ComplianceControlSpec spec(String id, Map<String, Object> props) {
        return new ComplianceControlSpec(
                id, "CERTIFICATE_EXPIRY", "CERTIFICATE_EXPIRY", "T", "D", List.of(),
                30, false, props);
    }

    private Path generateCert(Path dir, String filename, int validDays) throws Exception {
        Path ks = dir.resolve(filename + ".p12");
        Path cert = dir.resolve(filename);
        String alias = "test";

        var args = new java.util.ArrayList<>(List.of("keytool", "-genkeypair",
                "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=Test", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", "changeit",
                "-noprompt"));

        if (validDays < 0) {
            args.addAll(List.of("-startdate", "-10d", "-validity", "1"));
        } else {
            args.addAll(List.of("-validity", String.valueOf(validDays)));
        }

        new ProcessBuilder(args).redirectErrorStream(true).start().waitFor();

        new ProcessBuilder("keytool", "-exportcert", "-rfc",
                "-alias", alias, "-keystore", ks.toString(),
                "-storepass", "changeit", "-file", cert.toString())
                .redirectErrorStream(true).start().waitFor();
        return cert;
    }
}
