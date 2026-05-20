package com.anchr.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;

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
    private long createdAt;
    private long updatedAt;
    private long expiresAt;
}
