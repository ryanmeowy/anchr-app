package com.anchr.core.ingestion.domain.repository;

import com.anchr.core.ingestion.domain.model.IngestionTask;
import com.anchr.core.ingestion.domain.model.IngestionClaimContext;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.ingestion.domain.model.IngestionExecutionStage;
import com.anchr.core.ingestion.domain.model.IngestionTaskItem;
import com.anchr.core.ingestion.domain.model.IngestionTaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository boundary for ingestion tasks.
 */
public interface IngestionTaskRepository {

    void save(IngestionTask task);

    Optional<IngestionTask> findById(String kbId, String taskId);

    Optional<IngestionTask> findByClientRequestId(String createdBy, String clientRequestId);

    List<IngestionTask> list(String kbId, IngestionTaskStatus status, int limit);

    List<IngestionTask> listRecent(int limit);

    List<IngestionTaskItem> listItems(String taskId);

    List<IngestionTaskItem> listFailedItems(String kbId, String taskId);

    Optional<IngestionTaskItem> findItem(String kbId, String taskId, String itemId);

    List<String> listClaimableItemIds(int limit);

    List<String> listClaimableItemIds(String taskId, int limit);

    Optional<IngestionTaskItem> claimOne(String itemId, long leaseSeconds);

    boolean renewClaim(String itemId, long executionEpoch,
                       IngestionExecutionStage expectedExecutionStage,
                       int stageAttempt, String leaseToken, long leaseSeconds);

    boolean updateClaimContext(IngestionClaimContext context);

    boolean transitionClaim(IngestionClaimTransition transition);

    /**
     * Locks and validates a claim inside an existing transaction.
     *
     * <p>The caller must already own the transaction that contains the related
     * asset/index write. Lease expiry alone does not invalidate a claim; a newer
     * claimant changes the token and/or stage attempt.</p>
     */
    boolean isClaimCurrentForUpdate(String itemId, long executionEpoch,
                                    IngestionExecutionStage expectedExecutionStage,
                                    int stageAttempt, String leaseToken);

    boolean resetFailedItem(String kbId, String taskId, String itemId,
                            int expectedParseAttempt, int nextParseAttempt,
                            String nextDoclingRequestId, LocalDateTime updatedAt);

    void refreshSummary(String kbId, String taskId, String updatedBy, LocalDateTime updatedAt);
}
