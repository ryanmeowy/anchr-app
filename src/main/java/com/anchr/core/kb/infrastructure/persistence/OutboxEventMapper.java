package com.anchr.core.kb.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis mapper for outbox_event.
 */
@Mapper
public interface OutboxEventMapper {

    int insert(OutboxEventRecord record);

    List<OutboxEventRecord> selectClaimableForUpdate(@Param("now") LocalDateTime now,
                                                      @Param("expiredBefore") LocalDateTime expiredBefore,
                                                      @Param("limit") int limit);

    int markProcessing(@Param("id") long id,
                       @Param("lockToken") String lockToken,
                       @Param("lockedAt") LocalDateTime lockedAt);

    int markDone(@Param("id") long id,
                 @Param("lockToken") String lockToken,
                 @Param("processedAt") LocalDateTime processedAt);

    int markRetry(@Param("id") long id,
                  @Param("lockToken") String lockToken,
                  @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("lastError") String lastError,
                  @Param("updatedAt") LocalDateTime updatedAt);

    int markFailed(@Param("id") long id,
                   @Param("lockToken") String lockToken,
                   @Param("retryCount") int retryCount,
                   @Param("lastError") String lastError,
                   @Param("updatedAt") LocalDateTime updatedAt);

    int deleteDoneBefore(@Param("processedBefore") LocalDateTime processedBefore,
                         @Param("limit") int limit);
}
