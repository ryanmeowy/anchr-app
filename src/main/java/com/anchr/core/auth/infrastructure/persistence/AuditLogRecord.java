package com.anchr.core.auth.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLogRecord {
    private String id;
    private String workspaceId;
    private String userId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String outcome;
    private String payload;
    private LocalDateTime createdAt;
}
