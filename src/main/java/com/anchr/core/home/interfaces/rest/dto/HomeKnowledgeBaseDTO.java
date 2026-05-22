package com.anchr.core.home.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Knowledge base summary item for home page.
 */
@Value
@Builder
public class HomeKnowledgeBaseDTO {

    String kbId;
    String name;
    int documentCount;
    int segmentCount;
    LocalDateTime updatedAt;
}
