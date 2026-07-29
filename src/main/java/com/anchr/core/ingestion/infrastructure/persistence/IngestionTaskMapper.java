package com.anchr.core.ingestion.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** MyBatis mapper for the two ingestion tables. */
@Mapper
public interface IngestionTaskMapper {

    int insertTask(IngestionTaskRecord record);

    int insertItem(IngestionTaskItemRecord record);

    Optional<IngestionTaskRecord> findTask(@Param("kbId") String kbId,
                                           @Param("taskId") String taskId);

    Optional<IngestionTaskRecord> findTaskByClientRequestId(
            @Param("createdBy") String createdBy,
            @Param("clientRequestId") String clientRequestId);

    List<IngestionTaskRecord> listTasks(@Param("kbId") String kbId,
                                        @Param("status") String status,
                                        @Param("limit") int limit);

    List<IngestionTaskRecord> listRecentTasks(@Param("limit") int limit);

    List<IngestionTaskItemRecord> listItems(@Param("taskId") String taskId);

    List<IngestionTaskItemRecord> listFailedItems(@Param("kbId") String kbId,
                                                  @Param("taskId") String taskId);

    List<IngestionTaskItemRecord> listRunningItems();

    Optional<IngestionTaskItemRecord> findItem(@Param("kbId") String kbId,
                                               @Param("taskId") String taskId,
                                               @Param("itemId") String itemId);

    Optional<IngestionTaskItemRecord> findRetryItem(@Param("kbId") String kbId,
                                                    @Param("taskId") String taskId,
                                                    @Param("itemId") String itemId);

    List<String> listPendingItemIds(@Param("limit") int limit);

    List<String> listPendingItemIdsByTask(@Param("taskId") String taskId,
                                          @Param("limit") int limit);

    int claimPending(@Param("itemId") String itemId);

    Optional<IngestionTaskItemRecord> findRunningItem(@Param("itemId") String itemId);

    int advanceRunningItem(@Param("kbId") String kbId,
                           @Param("taskId") String taskId,
                           @Param("itemId") String itemId,
                           @Param("expectedStage") String expectedStage,
                           @Param("nextStage") String nextStage,
                           @Param("progress") int progress,
                           @Param("updatedAt") LocalDateTime updatedAt);

    Optional<String> findRunningItemForUpdate(@Param("itemId") String itemId,
                                              @Param("expectedStage") String expectedStage);

    int completeRunningItem(@Param("kbId") String kbId,
                            @Param("taskId") String taskId,
                            @Param("itemId") String itemId,
                            @Param("expectedStage") String expectedStage,
                            @Param("updatedAt") LocalDateTime updatedAt);

    int failRunningItem(@Param("kbId") String kbId,
                        @Param("taskId") String taskId,
                        @Param("itemId") String itemId,
                        @Param("expectedStage") String expectedStage,
                        @Param("progress") int progress,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage,
                        @Param("updatedAt") LocalDateTime updatedAt);

    int resetFailedItem(@Param("kbId") String kbId,
                        @Param("taskId") String taskId,
                        @Param("itemId") String itemId,
                        @Param("nextTargetIndexGeneration") long nextTargetIndexGeneration,
                        @Param("updatedAt") LocalDateTime updatedAt);

    Long findMaxTargetIndexGeneration(@Param("assetId") String assetId);

    List<Long> listTargetIndexGenerations(@Param("assetId") String assetId);

    Optional<Long> findTargetIndexGeneration(@Param("itemId") String itemId,
                                             @Param("assetId") String assetId);

    int assignTargetIndexGeneration(@Param("itemId") String itemId,
                                    @Param("assetId") String assetId,
                                    @Param("targetIndexGeneration") long targetIndexGeneration,
                                    @Param("updatedAt") LocalDateTime updatedAt);

    int refreshSummary(@Param("kbId") String kbId,
                       @Param("taskId") String taskId,
                       @Param("updatedBy") String updatedBy,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
