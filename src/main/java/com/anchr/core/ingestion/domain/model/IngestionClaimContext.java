package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

/**
 * Identity and stable request data written while an ingestion claim remains held.
 */
@Value
@Builder
public class IngestionClaimContext {

    String itemId;
    long executionEpoch;
    IngestionExecutionStage expectedExecutionStage;
    long claimVersion;
    String leaseToken;
    int parseAttempt;
    String doclingRequestId;
    String doclingJobId;
    String sourceRevision;
    String parseRequestSnapshot;
}
