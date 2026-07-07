package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@ApplicationScoped
public class ConfigHashEvidenceCollector implements EvidenceCollector {

    @Override
    public String strategy() {
        return "CONFIG_HASH";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        Object filePathObj = spec.properties().get("filePath");
        if (filePathObj == null) {
            return new EvidenceResult.Unavailable("missing required property: filePath");
        }
        Object expectedHashObj = spec.properties().get("expectedHash");
        if (expectedHashObj == null) {
            return new EvidenceResult.Unavailable("missing required property: expectedHash");
        }

        Path path = Path.of(filePathObj.toString());
        if (!Files.exists(path)) {
            return new EvidenceResult.Unavailable("file not found: " + path);
        }

        String algorithm = resolveAlgorithm(spec);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            return new EvidenceResult.Unavailable("unsupported algorithm: " + algorithm);
        }

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(path);
        } catch (IOException e) {
            return new EvidenceResult.Unavailable("cannot read file: " + e.getMessage());
        }

        String actualHash = HexFormat.of().formatHex(md.digest(fileBytes));
        String expectedHash = expectedHashObj.toString();

        if (actualHash.equalsIgnoreCase(expectedHash)) {
            return new EvidenceResult.Pass("hash match (" + algorithm + "): " + actualHash);
        }

        return new EvidenceResult.Fail("hash mismatch (" + algorithm + "): expected " + expectedHash + ", actual " + actualHash);
    }

    private String resolveAlgorithm(ComplianceControlSpec spec) {
        Object algo = spec.properties().get("algorithm");
        return algo != null ? algo.toString() : "SHA-256";
    }
}
