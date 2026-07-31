package com.anchr.core.kb.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;

public record OutboxRuntimeSettings(
        int batchSize,
        int maxAttempts,
        long retentionDays,
        int cleanupBatchSize
) {
    public static OutboxRuntimeSettings load(RuntimeConfigUnit unit) {
        return new OutboxRuntimeSettings(
                unit.getInt("OUTBOX", "batchSize", 20),
                unit.getInt("OUTBOX", "maxAttempts", 10),
                unit.getLong("OUTBOX", "retentionDays", 90L),
                unit.getInt("OUTBOX", "cleanupBatchSize", 1000));
    }
}
