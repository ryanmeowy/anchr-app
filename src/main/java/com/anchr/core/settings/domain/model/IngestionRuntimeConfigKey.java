package com.anchr.core.settings.domain.model;

public enum IngestionRuntimeConfigKey implements RuntimeConfigKey {
    CLAIM_BATCH_SIZE("claimBatchSize"),
    PARSE_POLL_INTERVAL_SECONDS("parsePollIntervalSeconds"),
    PARSE_STAGE_TIMEOUT_MINUTES("parseStageTimeoutMinutes"),
    STAGE_MAX_RETRIES("stageMaxRetries"),
    EMBEDDING_MIN_INTERVAL_MS("embeddingMinIntervalMs"),
    EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS("embeddingRateLimitMaxAttempts"),
    EMBEDDING_RATE_LIMIT_BACKOFF_MS("embeddingRateLimitBackoffMs"),
    CHUNK_MIN_TOKENS("chunkMinTokens"),
    CHUNK_MAX_TOKENS("chunkMaxTokens"),
    EMBEDDED_IMAGE_UPLOAD_ENABLED("embeddedImageUploadEnabled"),
    DOCLING_MAX_RESPONSE_MIB("doclingMaxResponseMiB");

    private final String propertyName;

    IngestionRuntimeConfigKey(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public RuntimeConfigType type() {
        return RuntimeConfigType.INGESTION;
    }

    @Override
    public String propertyName() {
        return propertyName;
    }
}
