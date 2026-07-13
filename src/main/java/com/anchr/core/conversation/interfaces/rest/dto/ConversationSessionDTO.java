package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Conversation session response DTO.
 */
@Data
public class ConversationSessionDTO implements Serializable {

    private String sessionId;
    private String userId;
    private String title;
    private String status;
    private String lastMessagePreview;
    private List<String> kbScope;
    private List<String> assetScope;
    private long createdAt;
    private long updatedAt;
    private Long expiresAt;
}
