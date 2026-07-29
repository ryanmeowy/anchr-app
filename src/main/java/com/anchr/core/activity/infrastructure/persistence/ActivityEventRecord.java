package com.anchr.core.activity.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistence record for activity_event.
 */
@Data
public class ActivityEventRecord {

    private String id;
    private String userId;
    private String eventType;
    private String resourceType;
    private String resourceId;
    private String payload;
    private LocalDateTime createdAt;
}
