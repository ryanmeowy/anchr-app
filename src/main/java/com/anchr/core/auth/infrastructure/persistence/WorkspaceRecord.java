package com.anchr.core.auth.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkspaceRecord {
    private String id;
    private String name;
    private String status;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
