package com.anchr.core.conversation.infrastructure.persistence.mysql;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationSessionRecord {
    private String sessionId;
    private String userId;
    private String title;
    private String status;
    private String kbScope;
    private String assetScope;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
