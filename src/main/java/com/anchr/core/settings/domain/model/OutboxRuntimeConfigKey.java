package com.anchr.core.settings.domain.model;

public enum OutboxRuntimeConfigKey implements RuntimeConfigKey {
    BATCH_SIZE("batchSize"),
    MAX_ATTEMPTS("maxAttempts"),
    RETENTION_DAYS("retentionDays"),
    CLEANUP_BATCH_SIZE("cleanupBatchSize");

    private final String propertyName;

    OutboxRuntimeConfigKey(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public RuntimeConfigType type() {
        return RuntimeConfigType.OUTBOX;
    }

    @Override
    public String propertyName() {
        return propertyName;
    }
}
