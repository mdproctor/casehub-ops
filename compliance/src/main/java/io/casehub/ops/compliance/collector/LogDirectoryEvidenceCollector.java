package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class LogDirectoryEvidenceCollector implements EvidenceCollector {

    @Override
    public String strategy() {
        return "LOG_DIRECTORY";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        Object logDirObj = spec.properties().get("logDirectory");
        if (logDirObj == null) {
            return new EvidenceResult.Unavailable("missing required property: logDirectory");
        }
        Object retentionObj = spec.properties().get("retentionDays");
        if (retentionObj == null) {
            return new EvidenceResult.Unavailable("missing required property: retentionDays");
        }

        Path dir = Path.of(logDirObj.toString());
        if (!(retentionObj instanceof Number retentionNum)) {
            return new EvidenceResult.Unavailable("retentionDays must be a number");
        }
        int retentionDays = retentionNum.intValue();

        if (!Files.isDirectory(dir)) {
            return new EvidenceResult.Fail("directory not found: " + dir);
        }

        Instant now = Instant.now();
        int maxAgeDays = spec.evidenceMaxAgeDays();
        boolean hasRecent = false;
        boolean hasHistorical = false;
        boolean hasAnyFiles = false;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) continue;
                hasAnyFiles = true;
                Instant modified = Files.getLastModifiedTime(entry).toInstant();
                long ageDays = Duration.between(modified, now).toDays();
                if (ageDays <= maxAgeDays) {
                    hasRecent = true;
                }
                if (ageDays >= retentionDays) {
                    hasHistorical = true;
                }
            }
        } catch (IOException e) {
            return new EvidenceResult.Unavailable("cannot read directory: " + e.getMessage());
        }

        if (!hasAnyFiles) {
            return new EvidenceResult.Fail("directory empty: " + dir);
        }
        if (!hasRecent) {
            return new EvidenceResult.Fail("no recent files within " + maxAgeDays + " days");
        }
        if (!hasHistorical) {
            return new EvidenceResult.Fail("retention not satisfied: no files older than " + retentionDays + " days");
        }

        return new EvidenceResult.Pass("directory has recent activity and " + retentionDays + "-day retention");
    }
}
