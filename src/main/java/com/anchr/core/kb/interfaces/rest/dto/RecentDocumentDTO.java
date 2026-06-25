package com.anchr.core.kb.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Recent imported document task item.
 */
@Value
@Builder
public class RecentDocumentDTO {

    String taskId;
    String kbId;
    String knowledgeBaseName;
    String status;
    int totalCount;
    int successCount;
    int failureCount;
    int runningCount;
    LocalDateTime importedAt;
}
