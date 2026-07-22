package com.anchr.core.ingestion.infrastructure.persistence;

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

    int resetFailedItem(@Param("kbId") String kbId,
                        @Param("taskId") String taskId,
                        @Param("itemId") String itemId,
                        @Param("updatedAt") LocalDateTime updatedAt);

    int resetFailedItems(@Param("kbId") String kbId,
                         @Param("taskId") String taskId,
                         @Param("updatedAt") LocalDateTime updatedAt);

    int markItemRunning(@Param("kbId") String kbId,
                        @Param("taskId") String taskId,
                        @Param("itemId") String itemId,
                        @Param("stage") String stage,
                        @Param("progress") int progress,
                        @Param("updatedAt") LocalDateTime updatedAt);

    int markItemSuccess(@Param("kbId") String kbId,
                        @Param("taskId") String taskId,
                        @Param("itemId") String itemId,
                        @Param("stage") String stage,
                        @Param("progress") int progress,
                        @Param("updatedAt") LocalDateTime updatedAt);

    int markItemFailed(@Param("kbId") String kbId,
                       @Param("taskId") String taskId,
                       @Param("itemId") String itemId,
                       @Param("stage") String stage,
                       @Param("progress") int progress,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage,
                       @Param("updatedAt") LocalDateTime updatedAt);

    int refreshSummary(@Param("kbId") String kbId,
                       @Param("taskId") String taskId,
                       @Param("updatedBy") String updatedBy,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
