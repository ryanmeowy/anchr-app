package com.anchr.core.ingestion.infrastructure.persistence;

import lombok.Data;

/**
 * Narrow row-lock projection used while claiming one due execution.
 */
@Data
public class ClaimCandidateRecord {

    private String itemId;
    private Integer itemProgress;
    private Long executionId;
    private Long executionEpoch;
    private String phase;
    private Long claimVersion;
    private String leaseToken;
}
