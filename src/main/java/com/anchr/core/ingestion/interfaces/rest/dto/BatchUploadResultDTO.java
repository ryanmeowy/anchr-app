package com.anchr.core.ingestion.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Data Transfer Object for batch image upload results
 * This class encapsulates the results of a batch image upload operation,
 * including success/failure counts and detailed failure information.
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Data
@Builder
public class BatchUploadResultDTO implements Serializable {

    /**
     * Total count
     */
    private int total;

    /**
     * Success count
     */
    private int successCount;

    /**
     * Failure count
     */
    private int failureCount;

    /**
     * Failure details list
     */
    private List<BatchFailureItem> failures;

    @Data
    @Builder
    public static class BatchFailureItem {
        /**
         * Original filename
         */
        private String filename;
        /**
         * Error reason
         */
        private String errorMessage;
        /**
         * OSS object key
         */
        private String objectKey;
    }
}