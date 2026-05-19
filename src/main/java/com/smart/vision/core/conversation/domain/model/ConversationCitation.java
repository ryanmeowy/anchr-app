package com.smart.vision.core.conversation.domain.model;

import lombok.Data;

/**
 * Citation model for conversation answer grounding.
 */
@Data
public class ConversationCitation {

    private String fileName;
    private Integer pageNo;
    private String snippet;
    private String hitType;
    private String assetId;
    private String segmentId;
}

