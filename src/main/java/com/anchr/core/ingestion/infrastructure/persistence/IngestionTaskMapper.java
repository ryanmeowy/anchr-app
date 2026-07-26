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
 *
 * <p>Public reads, claim candidates, claimed executions and retries deliberately
 * use different records. This keeps lease, request-snapshot and artifact data
 * out of REST/list queries and keeps large source/error fields out of the claim
 * scan and row-lock query.</p>
 */
@Mapper
public interface IngestionTaskMapper {

    int insertTask(IngestionTaskRecord record);

    int insertItem(IngestionTaskItemRecord record);

    int insertParseAttempt(IngestionParseAttemptRecord record);

    int insertExecution(IngestionExecutionRecord record);

    int insertArtifact(IngestionArtifactRecord record);

    int pointItemToExecution(@Param("itemId") String itemId,
                             @Param("executionId") Long executionId,
                             @Param("updatedAt") LocalDateTime updatedAt);

    Optional<IngestionTaskRecord> findTask(@Param("kbId") String kbId,
                                           @Param("taskId") String taskId);

    Optional<IngestionTaskRecord> findTaskByClientRequestId(@Param("createdBy") String createdBy,
                                                            @Param("clientRequestId") String clientRequestId);

    List<IngestionTaskRecord> listTasks(@Param("kbId") String kbId,
                                        @Param("status") String status,
                                        @Param("limit") int limit);

    List<IngestionTaskRecord> listRecentTasks(@Param("limit") int limit);

    List<IngestionItemViewRecord> listItems(@Param("taskId") String taskId);

    List<FailedItemRetryRecord> listFailedItems(@Param("kbId") String kbId,
                                                @Param("taskId") String taskId);

    Optional<IngestionItemViewRecord> findItem(@Param("kbId") String kbId,
                                               @Param("taskId") String taskId,
                                               @Param("itemId") String itemId);

    Optional<FailedItemRetryRecord> findRetryItem(@Param("kbId") String kbId,
                                                  @Param("taskId") String taskId,
                                                  @Param("itemId") String itemId);

    List<String> listClaimableItemIds(@Param("limit") int limit);

    List<String> listClaimableItemIdsByTask(@Param("taskId") String taskId,
                                            @Param("limit") int limit);

    Optional<ClaimCandidateRecord> selectClaimableItemForUpdate(@Param("itemId") String itemId);

    int claimExecution(@Param("candidate") ClaimCandidateRecord candidate,
                       @Param("leaseToken") String leaseToken,
                       @Param("leaseSeconds") long leaseSeconds);

    int projectClaimedItem(@Param("itemId") String itemId,
                           @Param("executionId") Long executionId,
                           @Param("projection")
                           com.anchr.core.ingestion.domain.model.IngestionPublicProjection projection);

    Optional<ClaimedExecutionRecord> findClaimedExecution(
            @Param("itemId") String itemId,
            @Param("leaseToken") String leaseToken,
            @Param("includeParseSnapshot") boolean includeParseSnapshot);

    Long findMaxTargetIndexGeneration(@Param("assetId") String assetId);

    Optional<Long> findTargetIndexGeneration(@Param("itemId") String itemId,
                                             @Param("assetId") String assetId);

    int assignTargetIndexGeneration(@Param("itemId") String itemId,
                                    @Param("assetId") String assetId,
                                    @Param("targetIndexGeneration") long targetIndexGeneration,
                                    @Param("updatedAt") LocalDateTime updatedAt);

    int renewClaim(@Param("itemId") String itemId,
                   @Param("executionEpoch") long executionEpoch,
                   @Param("expectedExecutionStage") IngestionExecutionStage expectedExecutionStage,
                   @Param("claimVersion") long claimVersion,
                   @Param("leaseToken") String leaseToken,
                   @Param("leaseSeconds") long leaseSeconds);

    int updateClaimContext(IngestionClaimContext context);

    int transitionExecution(IngestionClaimTransition transition);

    int projectTransitionToItem(IngestionClaimTransition transition);

    int updateParseAttemptFromTransition(IngestionClaimTransition transition);

    Optional<Long> findCurrentExecutionId(@Param("itemId") String itemId,
                                          @Param("executionEpoch") long executionEpoch);

    Optional<IngestionArtifactRecord> findArtifact(
            @Param("executionId") Long executionId,
            @Param("artifactType") String artifactType);

    List<IngestionArtifactRecord> listArtifactsByAssetGeneration(
            @Param("assetId") String assetId,
            @Param("targetIndexGeneration") Long targetIndexGeneration,
            @Param("artifactType") String artifactType);

    Optional<Long> findCurrentClaimForUpdate(
            @Param("itemId") String itemId,
            @Param("executionEpoch") long executionEpoch,
            @Param("expectedExecutionStage") IngestionExecutionStage expectedExecutionStage,
            @Param("claimVersion") long claimVersion,
            @Param("leaseToken") String leaseToken);

    Optional<FailedItemRetryRecord> selectFailedItemForRetryForUpdate(
            @Param("kbId") String kbId,
            @Param("taskId") String taskId,
            @Param("itemId") String itemId,
            @Param("expectedParseAttempt") int expectedParseAttempt);

    int resetFailedItemPointer(@Param("kbId") String kbId,
                               @Param("taskId") String taskId,
                               @Param("itemId") String itemId,
                               @Param("expectedCurrentExecutionId") Long expectedCurrentExecutionId,
                               @Param("nextExecutionId") Long nextExecutionId,
                               @Param("projection")
                               com.anchr.core.ingestion.domain.model.IngestionPublicProjection projection,
                               @Param("updatedAt") LocalDateTime updatedAt);

    int refreshSummary(@Param("kbId") String kbId,
                       @Param("taskId") String taskId,
                       @Param("updatedBy") String updatedBy,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
