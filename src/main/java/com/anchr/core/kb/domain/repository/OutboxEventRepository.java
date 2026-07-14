package com.anchr.core.kb.domain.repository;

import com.anchr.core.kb.domain.model.OutboxEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence boundary for the transactional outbox.
 */
public interface OutboxEventRepository {

    void save(OutboxEvent event);

    List<OutboxEvent> claimAvailable(LocalDateTime now,
                                     LocalDateTime expiredBefore,
                                     int limit,
                                     String lockToken);

    boolean markDone(long id, String lockToken, LocalDateTime processedAt);

    boolean markRetry(long id, String lockToken, int retryCount,
                      LocalDateTime nextRetryAt, String lastError, LocalDateTime updatedAt);

    boolean markFailed(long id, String lockToken, int retryCount,
                       String lastError, LocalDateTime updatedAt);

    int deleteDoneBefore(LocalDateTime processedBefore, int limit);
}
