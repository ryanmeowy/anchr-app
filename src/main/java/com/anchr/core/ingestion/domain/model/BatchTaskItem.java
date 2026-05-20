package com.anchr.core.ingestion.domain.model;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain model for one item in batch task.
 */
@Data
@NoArgsConstructor
public class BatchTaskItem {

    private String itemId;
    private AssetType assetType;
    private String key;
    private String fileName;
    private String fileHash;
    private BatchTaskItemStatus status;
    private String errorMessage;
    private int retryCount;
    private long updatedAt;

    public void markRunning(long now) {
        this.status = BatchTaskItemStatus.RUNNING;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    public void markSuccess(long now) {
        this.status = BatchTaskItemStatus.SUCCESS;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    public void markFailed(BatchTaskItemError error, long now) {
        markFailed(error.getMessage(), now);
    }

    public void markFailed(String message, long now) {
        this.status = BatchTaskItemStatus.FAILED;
        this.errorMessage = message;
        this.updatedAt = now;
    }

    public void retry(long now) {
        if (this.status != BatchTaskItemStatus.FAILED) {
            throw new BusinessException(ApiError.INGEST_RETRY_ONLY_FAILED);
        }
        this.status = BatchTaskItemStatus.PENDING;
        this.errorMessage = null;
        this.retryCount += 1;
        this.updatedAt = now;
    }
}
