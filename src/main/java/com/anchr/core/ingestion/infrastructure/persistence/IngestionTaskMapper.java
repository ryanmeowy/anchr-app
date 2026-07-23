package com.anchr.core.ingestion.infrastructure.persistence;

import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper for ingestion task persistence.
 */
@Mapper
public interface IngestionTaskMapper {

    int insertTask(IngestionTaskRecord record);

    int insertItem(IngestionTaskItemRecord record);

    Optional<IngestionTaskRecord> findTask(@Param("kbId") String kbId,
                                           @Param("taskId") String taskId);

    Optional<IngestionTaskRecord> findTaskByClientRequestId(@Param("createdBy") String createdBy,
                                                            @Param("clientRequestId") String clientRequestId);

    List<IngestionTaskRecord> listTasks(@Param("kbId") String kbId,
                                        @Param("status") String status,
                                        @Param("limit") int limit);

    List<IngestionTaskRecord> listRecentTasks(@Param("limit") int limit);

    List<IngestionTaskItemRecord> listItems(@Param("taskId") String taskId);

    List<IngestionTaskItemRecord> listFailedItems(@Param("kbId") String kbId,
                                                  @Param("taskId") String taskId);

    Optional<IngestionTaskItemRecord> findItem(@Param("kbId") String kbId,
                                               @Param("taskId") String taskId,
                                               @Param("itemId") String itemId);

    List<String> listClaimableItemIds(@Param("limit") int limit);

    List<String> listClaimableItemIdsByTask(@Param("taskId") String taskId,
                                            @Param("limit") int limit);

    Optional<IngestionTaskItemRecord> selectClaimableItemForUpdate(@Param("itemId") String itemId);

    int claimItem(@Param("item") IngestionTaskItemRecord item,
                  @Param("leaseToken") String leaseToken,
                  @Param("leaseSeconds") long leaseSeconds);

    Optional<IngestionTaskItemRecord> findClaimedItem(@Param("itemId") String itemId,
                                                      @Param("leaseToken") String leaseToken);

    int renewClaim(@Param("itemId") String itemId,
                   @Param("executionEpoch") long executionEpoch,
                   @Param("expectedExecutionStage") IngestionExecutionStage expectedExecutionStage,
                   @Param("stageAttempt") int stageAttempt,
                   @Param("leaseToken") String leaseToken,
                   @Param("leaseSeconds") long leaseSeconds);

    int updateClaimContext(IngestionClaimContext context);

    int transitionClaim(IngestionClaimTransition transition);

    Optional<String> findCurrentClaimForUpdate(
            @Param("itemId") String itemId,
            @Param("executionEpoch") long executionEpoch,
            @Param("expectedExecutionStage") IngestionExecutionStage expectedExecutionStage,
            @Param("stageAttempt") int stageAttempt,
            @Param("leaseToken") String leaseToken);

    int resetFailedItem(@Param("kbId") String kbId,
                        @Param("taskId") String taskId,
                        @Param("itemId") String itemId,
                        @Param("expectedParseAttempt") int expectedParseAttempt,
                        @Param("nextParseAttempt") int nextParseAttempt,
                        @Param("nextDoclingRequestId") String nextDoclingRequestId,
                        @Param("updatedAt") LocalDateTime updatedAt);

    int refreshSummary(@Param("kbId") String kbId,
                       @Param("taskId") String taskId,
                       @Param("updatedBy") String updatedBy,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
