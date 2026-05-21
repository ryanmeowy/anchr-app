package com.anchr.core.kb.interfaces.rest.dto;

import com.anchr.core.kb.domain.model.KnowledgeBase;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Knowledge base response DTO.
 */
@Value
@Builder
public class KnowledgeBaseDTO {

    String id;
    String name;
    String description;
    String status;
    int documentCount;
    int segmentCount;
    LocalDateTime lastIngestedAt;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static KnowledgeBaseDTO from(KnowledgeBase knowledgeBase) {
        return KnowledgeBaseDTO.builder()
                .id(knowledgeBase.getId())
                .name(knowledgeBase.getName())
                .description(knowledgeBase.getDescription())
                .status(knowledgeBase.getStatus().name())
                .documentCount(knowledgeBase.getDocumentCount())
                .segmentCount(knowledgeBase.getSegmentCount())
                .lastIngestedAt(knowledgeBase.getLastIngestedAt())
                .createdAt(knowledgeBase.getCreatedAt())
                .updatedAt(knowledgeBase.getUpdatedAt())
                .build();
    }
}
