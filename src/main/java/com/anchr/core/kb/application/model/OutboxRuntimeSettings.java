package com.anchr.core.kb.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.RuntimeConfigType;

import static com.anchr.core.settings.domain.model.OutboxRuntimeConfigKey.BATCH_SIZE;
import static com.anchr.core.settings.domain.model.OutboxRuntimeConfigKey.CLEANUP_BATCH_SIZE;
import static com.anchr.core.settings.domain.model.OutboxRuntimeConfigKey.MAX_ATTEMPTS;
import static com.anchr.core.settings.domain.model.OutboxRuntimeConfigKey.RETENTION_DAYS;

public record OutboxRuntimeSettings(
        int batchSize,
        int maxAttempts,
        long retentionDays,
        int cleanupBatchSize
) {
    public static OutboxRuntimeSettings load(RuntimeConfigUnit unit) {
        return new OutboxRuntimeSettings(
                unit.getInt(RuntimeConfigType.OUTBOX, BATCH_SIZE, 20),
                unit.getInt(RuntimeConfigType.OUTBOX, MAX_ATTEMPTS, 10),
                unit.getLong(RuntimeConfigType.OUTBOX, RETENTION_DAYS, 90L),
                unit.getInt(RuntimeConfigType.OUTBOX, CLEANUP_BATCH_SIZE, 1000));
    }
}
