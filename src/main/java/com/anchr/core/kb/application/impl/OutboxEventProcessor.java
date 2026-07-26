package com.anchr.core.kb.application.impl;

import com.anchr.core.kb.domain.model.DocumentIndexDeletePayload;
import com.anchr.core.kb.domain.model.DocumentIndexGenerationDeletePayload;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.anchr.core.ingestion.application.artifact.IngestionArtifactStore;
import com.anchr.core.ingestion.domain.model.IngestionArtifactReference;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.ingestion.domain.repository.IngestionTaskRepository;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Polls and executes durable outbox events without holding a database transaction
 * while calling an external system.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;
    private static final List<Long> RETRY_DELAYS_MINUTES = List.of(1L, 5L, 30L, 120L, 720L, 1440L);

    private final OutboxEventRepository outboxEventRepository;
    private final SegmentRepository segmentRepository;
    private final ObjectMapper objectMapper;
    private final IngestionTaskRepository ingestionTaskRepository;
    private final IngestionArtifactStore artifactStore;
    private final IngestionObjectStoragePort objectStoragePort;

    @Value("${app.outbox.batch-size:20}")
    private int batchSize;

    @Value("${app.outbox.lock-lease-minutes:5}")
    private long lockLeaseMinutes;

    @Value("${app.outbox.max-attempts:10}")
    private int maxAttempts;

    @Value("${app.outbox.retention-days:90}")
    private long retentionDays;

    @Value("${app.outbox.cleanup-batch-size:1000}")
    private int cleanupBatchSize;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:3000}",
            initialDelayString = "${app.outbox.poll-interval-ms:3000}")
    public void poll() {
        LocalDateTime now = LocalDateTime.now();
        String lockToken = UUID.randomUUID().toString();
        List<OutboxEvent> events = outboxEventRepository.claimAvailable(
                now, now.minusMinutes(lockLeaseMinutes), batchSize, lockToken);
        for (OutboxEvent event : events) {
            process(event);
        }
    }

    void process(OutboxEvent event) {
        try {
            switch (event.getEventType()) {
                case DELETE_ASSET -> {
                    DocumentIndexDeletePayload payload = readDeletePayload(event);
                    cleanupEmbeddedImageObjects(payload.assetId(), null);
                    segmentRepository.deleteByAssetId(payload.assetId());
                }
                case DELETE_ASSET_GENERATION -> {
                    DocumentIndexGenerationDeletePayload payload =
                            readGenerationDeletePayload(event);
                    cleanupEmbeddedImageObjects(
                            payload.assetId(), payload.indexGeneration());
                    segmentRepository.deleteByAssetGeneration(
                            payload.assetId(), payload.indexGeneration());
                }
                case UNKNOWN -> {
                    failPermanently(
                            event,
                            "Unsupported outbox event type: " + event.getEventType());
                    return;
                }
            }
            boolean updated = outboxEventRepository.markDone(
                    event.getId(), event.getLockToken(), LocalDateTime.now());
            if (!updated) {
                log.warn("outbox completion ignored because claim expired, eventId={}, lockToken={}",
                        event.getId(), event.getLockToken());
            }
        } catch (PermanentEventException e) {
            failPermanently(event, e.getMessage());
        } catch (Exception e) {
            retryOrFail(event, e);
        }
    }

    private void cleanupEmbeddedImageObjects(String assetId, Long indexGeneration) {
        LinkedHashSet<String> objectKeys = new LinkedHashSet<>();
        List<IngestionArtifactReference> parseArtifacts =
                ingestionTaskRepository.listParseArtifacts(assetId, indexGeneration);
        for (IngestionArtifactReference artifact : parseArtifacts) {
            objectKeys.addAll(
                    artifactStore.readEmbeddedImageObjectKeys(artifact, assetId));
        }
        for (String objectKey : objectKeys) {
            objectStoragePort.deleteObject(objectKey);
        }
    }

    @Scheduled(
            cron = "${app.outbox.cleanup-cron:0 0 3 * * *}" ,
            zone = "Asia/Shanghai"
    )
    public void cleanupDoneEvents() {
        LocalDateTime processedBefore = LocalDateTime.now().minusDays(retentionDays);
        int deleted = outboxEventRepository.deleteDoneBefore(processedBefore, cleanupBatchSize);
        if (deleted > 0) {
            log.info("cleaned completed outbox events, count={}, processedBefore={}", deleted, processedBefore);
        }
    }

    private DocumentIndexDeletePayload readDeletePayload(OutboxEvent event) {
        try {
            DocumentIndexDeletePayload payload = objectMapper.readValue(
                    event.getPayload(), DocumentIndexDeletePayload.class);
            if (payload == null
                    || !StringUtils.hasText(payload.kbId())
                    || !StringUtils.hasText(payload.assetId())
                    || !payload.assetId().trim().equals(event.getAggregateId())) {
                throw new PermanentEventException("Invalid document index delete payload.");
            }
            return new DocumentIndexDeletePayload(payload.kbId().trim(), payload.assetId().trim());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new PermanentEventException("Invalid document index delete payload.", e);
        }
    }

    private DocumentIndexGenerationDeletePayload readGenerationDeletePayload(
            OutboxEvent event) {
        try {
            DocumentIndexGenerationDeletePayload payload = objectMapper.readValue(
                    event.getPayload(), DocumentIndexGenerationDeletePayload.class);
            if (payload == null
                    || !StringUtils.hasText(payload.kbId())
                    || !StringUtils.hasText(payload.assetId())
                    || payload.indexGeneration() < 0L
                    || !payload.assetId().trim().equals(event.getAggregateId())) {
                throw new PermanentEventException(
                        "Invalid document index generation delete payload.");
            }
            return new DocumentIndexGenerationDeletePayload(
                    payload.kbId().trim(),
                    payload.assetId().trim(),
                    payload.indexGeneration());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new PermanentEventException(
                    "Invalid document index generation delete payload.", e);
        }
    }

    private void retryOrFail(OutboxEvent event, Exception exception) {
        int retryCount = event.getRetryCount() + 1;
        String error = clip(exception.getMessage());
        LocalDateTime now = LocalDateTime.now();
        if (retryCount >= maxAttempts) {
            boolean updated = outboxEventRepository.markFailed(
                    event.getId(), event.getLockToken(), retryCount, error, now);
            if (updated) {
                log.error("outbox event exhausted retries, eventId={}, eventType={}, retryCount={}, error={}",
                        event.getId(), event.getEventType(), retryCount, error, exception);
            }
            return;
        }
        LocalDateTime nextRetryAt = now.plusMinutes(retryDelayMinutes(retryCount));
        boolean updated = outboxEventRepository.markRetry(
                event.getId(), event.getLockToken(), retryCount, nextRetryAt, error, now);
        if (updated) {
            log.warn("outbox event scheduled for retry, eventId={}, eventType={}, retryCount={}, nextRetryAt={}, error={}",
                    event.getId(), event.getEventType(), retryCount, nextRetryAt, error);
        }
    }

    private void failPermanently(OutboxEvent event, String error) {
        LocalDateTime now = LocalDateTime.now();
        boolean updated = outboxEventRepository.markFailed(
                event.getId(), event.getLockToken(), event.getRetryCount(), clip(error), now);
        if (updated) {
            log.error("invalid outbox event moved to failed, eventId={}, eventType={}, error={}",
                    event.getId(), event.getEventType(), error);
        }
    }

    private long retryDelayMinutes(int retryCount) {
        int index = Math.max(0, Math.min(retryCount - 1, RETRY_DELAYS_MINUTES.size() - 1));
        return RETRY_DELAYS_MINUTES.get(index);
    }

    private String clip(String message) {
        String value = StringUtils.hasText(message) ? message : "Unknown outbox processing error.";
        return value.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? value
                : value.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    private static final class PermanentEventException extends RuntimeException {
        private PermanentEventException(String message) {
            super(message);
        }

        private PermanentEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
