package com.anchr.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder
public class BulkSaveResult {
    int successCount;
    Set<String> successIds;
    Set<String> failedIds;
}
