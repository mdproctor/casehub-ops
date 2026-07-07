package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class FileExistenceEvidenceCollector implements EvidenceCollector {

    @Override
    public String strategy() {
        return "FILE_EXISTENCE";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        Object filePathObj = spec.properties().get("filePath");
        if (filePathObj == null) {
            return new EvidenceResult.Unavailable("missing required property: filePath");
        }

        Path path = Path.of(filePathObj.toString());
        if (!Files.exists(path)) {
            return new EvidenceResult.Fail("file not found: " + path);
        }

        if (!Files.isReadable(path)) {
            return new EvidenceResult.Unavailable("file not readable: " + path);
        }

        int maxAgeDays = resolveMaxAgeDays(spec);
        Instant lastModified;
        try {
            lastModified = Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return new EvidenceResult.Unavailable("cannot read file metadata: " + e.getMessage());
        }

        long ageDays = Duration.between(lastModified, Instant.now()).toDays();
        if (ageDays > maxAgeDays) {
            return new EvidenceResult.Fail("file stale: " + ageDays + " days old (max " + maxAgeDays + ")");
        }

        return new EvidenceResult.Pass("file exists and is " + ageDays + " days old (max " + maxAgeDays + ")");
    }

    private int resolveMaxAgeDays(ComplianceControlSpec spec) {
        Object override = spec.properties().get("maxAgeDays");
        if (override instanceof Number n) {
            return n.intValue();
        }
        return spec.evidenceMaxAgeDays();
    }
}
