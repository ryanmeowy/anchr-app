package com.anchr.core.auth.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserAccountRecord {
    private String id;
    private String email;
    private String displayName;
    private String passwordHash;
    private String status;
    private String externalIssuer;
    private String externalSubject;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
