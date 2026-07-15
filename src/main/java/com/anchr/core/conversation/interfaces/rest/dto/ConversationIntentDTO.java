package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ConversationIntentDTO implements Serializable {
    private String type;
    private Double confidence;
    private String reason;
    private String source;
    private boolean fallbackUsed;
    private boolean retrievalRequired;
}
