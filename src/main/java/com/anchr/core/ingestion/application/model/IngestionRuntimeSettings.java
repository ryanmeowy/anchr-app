package com.anchr.core.ingestion.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import java.time.Duration;

import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.CLAIM_BATCH_SIZE;
import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.DOCLING_MAX_RESPONSE_MIB;
import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.EMBEDDED_IMAGE_UPLOAD_ENABLED;
import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.EMBEDDING_MIN_INTERVAL_MS;
import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.EMBEDDING_RATE_LIMIT_BACKOFF_MS;
import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS;
import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.PARSE_POLL_INTERVAL_SECONDS;
import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.PARSE_STAGE_TIMEOUT_MINUTES;
import static com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey.STAGE_MAX_RETRIES;

public record IngestionRuntimeSettings(
        int claimBatchSize,
        Duration parsePollInterval,
        Duration parseStageTimeout,
        int stageMaxRetries,
        long embeddingMinIntervalMs,
        int embeddingRateLimitMaxAttempts,
        long embeddingRateLimitBackoffMs,
        boolean embeddedImageUploadEnabled,
        int doclingMaxResponseBytes
) {
    public static IngestionRuntimeSettings load(RuntimeConfigUnit unit) {
        long responseMiB = unit.getLong(
                RuntimeConfigType.INGESTION, DOCLING_MAX_RESPONSE_MIB, 256L);
        return new IngestionRuntimeSettings(
                unit.getInt(RuntimeConfigType.INGESTION, CLAIM_BATCH_SIZE, 32),
                unit.getDurationSeconds(
                        RuntimeConfigType.INGESTION, PARSE_POLL_INTERVAL_SECONDS,
                        Duration.ofSeconds(2)),
                unit.getDurationMinutes(
                        RuntimeConfigType.INGESTION, PARSE_STAGE_TIMEOUT_MINUTES,
                        Duration.ofMinutes(45)),
                unit.getInt(RuntimeConfigType.INGESTION, STAGE_MAX_RETRIES, 5),
                unit.getLong(
                        RuntimeConfigType.INGESTION, EMBEDDING_MIN_INTERVAL_MS, 1500L),
                unit.getInt(
                        RuntimeConfigType.INGESTION,
                        EMBEDDING_RATE_LIMIT_MAX_ATTEMPTS, 5),
                unit.getLong(
                        RuntimeConfigType.INGESTION,
                        EMBEDDING_RATE_LIMIT_BACKOFF_MS, 5000L),
                unit.getBoolean(
                        RuntimeConfigType.INGESTION,
                        EMBEDDED_IMAGE_UPLOAD_ENABLED, false),
                Math.toIntExact(responseMiB * 1024L * 1024L));
    }
}
