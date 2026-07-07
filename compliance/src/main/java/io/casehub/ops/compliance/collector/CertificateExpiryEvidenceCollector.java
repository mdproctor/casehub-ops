package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class CertificateExpiryEvidenceCollector implements EvidenceCollector {

    @Override
    public String strategy() {
        return "CERTIFICATE_EXPIRY";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        Object certPathObj = spec.properties().get("certPath");
        if (certPathObj == null) {
            return new EvidenceResult.Unavailable("missing required property: certPath");
        }

        Path path = Path.of(certPathObj.toString());
        if (!Files.exists(path)) {
            return new EvidenceResult.Unavailable("certificate file not found: " + path);
        }

        X509Certificate cert;
        try {
            cert = parseCertificate(path);
        } catch (Exception e) {
            return new EvidenceResult.Unavailable("cannot parse certificate: " + e.getMessage());
        }

        Instant now = Instant.now();
        Instant notAfter = cert.getNotAfter().toInstant();

        if (now.isAfter(notAfter)) {
            long daysExpired = Duration.between(notAfter, now).toDays();
            return new EvidenceResult.Fail("certificate expired " + daysExpired + " days ago");
        }

        int warningThresholdDays = resolveWarningThreshold(spec);
        long daysRemaining = Duration.between(now, notAfter).toDays();
        if (daysRemaining <= warningThresholdDays) {
            return new EvidenceResult.Fail("certificate within warning threshold: " + daysRemaining + " days remaining (threshold " + warningThresholdDays + ")");
        }

        return new EvidenceResult.Pass("certificate valid: " + daysRemaining + " days remaining");
    }

    private X509Certificate parseCertificate(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            return (X509Certificate) cf.generateCertificate(is);
        }
    }

    private int resolveWarningThreshold(ComplianceControlSpec spec) {
        Object override = spec.properties().get("warningThresholdDays");
        if (override instanceof Number n) {
            return n.intValue();
        }
        return 30;
    }
}
