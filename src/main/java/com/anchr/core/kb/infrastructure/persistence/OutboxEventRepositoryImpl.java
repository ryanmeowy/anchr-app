package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis-backed transactional outbox repository.
 */
@Repository
@RequiredArgsConstructor
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventMapper mapper;

    @Override
    public void save(OutboxEvent event) {
        OutboxEventRecord record = toRecord(event);
        mapper.insert(record);
        event.setId(record.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<OutboxEvent> claimAvailable(LocalDateTime now,
                                            LocalDateTime expiredBefore,
                                            int limit,
                                            String lockToken) {
        List<OutboxEventRecord> records = mapper.selectClaimableForUpdate(now, expiredBefore, limit);
        for (OutboxEventRecord record : records) {
            mapper.markProcessing(record.getId(), lockToken, now);
            record.setStatus(OutboxEventStatus.PROCESSING.name());
            record.setLockToken(lockToken);
            record.setLockedAt(now);
            record.setUpdatedAt(now);
        }
        return records.stream().map(this::toDomain).toList();
    }

    @Override
    public boolean markDone(long id, String lockToken, LocalDateTime processedAt) {
        return mapper.markDone(id, lockToken, processedAt) > 0;
    }

    @Override
    public boolean markRetry(long id, String lockToken, int retryCount,
                             LocalDateTime nextRetryAt, String lastError, LocalDateTime updatedAt) {
        return mapper.markRetry(id, lockToken, retryCount, nextRetryAt, lastError, updatedAt) > 0;
    }

    @Override
    public boolean markFailed(long id, String lockToken, int retryCount,
                              String lastError, LocalDateTime updatedAt) {
        return mapper.markFailed(id, lockToken, retryCount, lastError, updatedAt) > 0;
    }

    @Override
    public int deleteDoneBefore(LocalDateTime processedBefore, int limit) {
        return mapper.deleteDoneBefore(processedBefore, limit);
    }

    private OutboxEventRecord toRecord(OutboxEvent event) {
        OutboxEventRecord record = new OutboxEventRecord();
        record.setId(event.getId());
        record.setEventType(event.getEventType().name());
        record.setAggregateType(event.getAggregateType());
        record.setAggregateId(event.getAggregateId());
        record.setPayload(event.getPayload());
        record.setStatus(event.getStatus().name());
        record.setRetryCount(event.getRetryCount());
        record.setNextRetryAt(event.getNextRetryAt());
        record.setLockToken(event.getLockToken());
        record.setLockedAt(event.getLockedAt());
        record.setProcessedAt(event.getProcessedAt());
        record.setLastError(event.getLastError());
        record.setCreatedBy(event.getCreatedBy());
        record.setCreatedAt(event.getCreatedAt());
        record.setUpdatedAt(event.getUpdatedAt());
        return record;
    }

    private OutboxEvent toDomain(OutboxEventRecord record) {
        return OutboxEvent.builder()
                .id(record.getId())
                .eventType(OutboxEventType.fromCode(record.getEventType()))
                .aggregateType(record.getAggregateType())
                .aggregateId(record.getAggregateId())
                .payload(record.getPayload())
                .status(OutboxEventStatus.valueOf(record.getStatus()))
                .retryCount(record.getRetryCount() == null ? 0 : record.getRetryCount())
                .nextRetryAt(record.getNextRetryAt())
                .lockToken(record.getLockToken())
                .lockedAt(record.getLockedAt())
                .processedAt(record.getProcessedAt())
                .lastError(record.getLastError())
                .createdBy(record.getCreatedBy())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
