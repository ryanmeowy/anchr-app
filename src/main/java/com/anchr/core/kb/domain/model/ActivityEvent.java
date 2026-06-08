package com.anchr.core.kb.domain.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Lightweight user activity event.
 */
@Value
@Builder(toBuilder = true)
public class ActivityEvent {

    String id;
    String workspaceId;
    String userId;
    ActivityEventType eventType;
    String resourceType;
    String resourceId;
    String payload;
    LocalDateTime createdAt;
}
