package com.anchr.core.auth.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkspaceMemberRecord {
    private String workspaceId;
    private String userId;
    private String email;
    private String displayName;
    private String role;
    private String status;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
