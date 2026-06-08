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

    Optional<IngestionTaskRecord> findTask(@Param("workspaceId") String workspaceId,
                                           @Param("kbId") String kbId,
                                           @Param("taskId") String taskId);

    List<IngestionTaskRecord> listTasks(@Param("workspaceId") String workspaceId,
                                        @Param("kbId") String kbId,
                                        @Param("status") String status,
                                        @Param("limit") int limit);

    List<IngestionTaskRecord> listRecentTasks(@Param("workspaceId") String workspaceId,
                                              @Param("limit") int limit);

    List<IngestionTaskItemRecord> listItems(@Param("taskId") String taskId);

    List<IngestionTaskItemRecord> listFailedItems(@Param("workspaceId") String workspaceId,
                                                  @Param("kbId") String kbId,
                                                  @Param("taskId") String taskId);

    Optional<IngestionTaskItemRecord> findItem(@Param("workspaceId") String workspaceId,
                                               @Param("kbId") String kbId,
                                               @Param("taskId") String taskId,
                                               @Param("itemId") String itemId);

    int resetFailedItem(@Param("workspaceId") String workspaceId,
                        @Param("kbId") String kbId,
                        @Param("taskId") String taskId,
                        @Param("itemId") String itemId,
                        @Param("updatedAt") LocalDateTime updatedAt);

    int resetFailedItems(@Param("workspaceId") String workspaceId,
                         @Param("kbId") String kbId,
                         @Param("taskId") String taskId,
                         @Param("updatedAt") LocalDateTime updatedAt);

    int refreshSummary(@Param("workspaceId") String workspaceId,
                       @Param("kbId") String kbId,
                       @Param("taskId") String taskId,
                       @Param("updatedBy") String updatedBy,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
