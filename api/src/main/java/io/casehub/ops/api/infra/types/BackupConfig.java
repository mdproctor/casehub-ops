package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record BackupConfig(boolean enabled, int retentionDays, String schedule) {

    public BackupConfig {
        Objects.requireNonNull(schedule, "schedule");
    }
}
