package com.anchr.core.ingestion.interfaces.rest.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch task status DTO.
 *
 * @author Ryan
 * @since 2026/04/01
 */
@Data
@NoArgsConstructor
public class BatchTaskStatusDTO implements Serializable {

    private String taskId;

    /**
     * PENDING / RUNNING / SUCCESS / PARTIAL_FAILED / FAILED
     */
    private String status;

    private int total;
    private int successCount;
    private int failureCount;
    private int runningCount;
    private int pendingCount;

    private long createdAt;
    private long updatedAt;
    private Long completedAt;

    private List<ItemStatus> items = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class ItemStatus implements Serializable {
        private String itemId;
        private String assetType;
        private String key;
        private String fileName;
        private String fileHash;

        /**
         * PENDING / RUNNING / SUCCESS / FAILED
         */
        private String status;

        private String errorMessage;
        private int retryCount;
        private long updatedAt;
    }
}
