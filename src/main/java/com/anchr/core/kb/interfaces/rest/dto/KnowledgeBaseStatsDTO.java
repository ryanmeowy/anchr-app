package com.anchr.core.kb.interfaces.rest.dto;

import com.anchr.core.kb.domain.model.KnowledgeBaseStats;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Knowledge base stats response DTO.
 */
@Value
@Builder
public class KnowledgeBaseStatsDTO {

    String kbId;
    int documentCount;
    int segmentCount;
    LocalDateTime lastIngestedAt;
    String lastIngestionStatus;
    LocalDateTime updatedAt;

    public static KnowledgeBaseStatsDTO from(KnowledgeBaseStats stats) {
        return KnowledgeBaseStatsDTO.builder()
                .kbId(stats.getKbId())
                .documentCount(stats.getDocumentCount())
                .segmentCount(stats.getSegmentCount())
                .lastIngestedAt(stats.getLastIngestedAt())
                .lastIngestionStatus(stats.getLastIngestionStatus())
                .updatedAt(stats.getUpdatedAt())
                .build();
    }
}
