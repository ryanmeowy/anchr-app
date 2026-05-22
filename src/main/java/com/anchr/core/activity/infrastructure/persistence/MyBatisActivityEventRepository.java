package com.anchr.core.activity.infrastructure.persistence;

import com.anchr.core.activity.domain.model.ActivityEvent;
import com.anchr.core.activity.domain.model.ActivityEventType;
import com.anchr.core.activity.domain.repository.ActivityEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis implementation of activity event repository.
 */
@Repository
@RequiredArgsConstructor
public class MyBatisActivityEventRepository implements ActivityEventRepository {

    private final ActivityEventMapper mapper;

    @Override
    public void save(ActivityEvent event) {
        mapper.insert(toRecord(event));
    }

    @Override
    public List<ActivityEvent> listByType(String workspaceId, String userId,
                                          ActivityEventType eventType, int limit, int offset) {
        return mapper.listByType(workspaceId, userId, eventType.name(), limit, offset).stream()
                .map(this::toDomain)
                .toList();
    }

    private ActivityEventRecord toRecord(ActivityEvent event) {
        ActivityEventRecord record = new ActivityEventRecord();
        record.setId(event.getId());
        record.setWorkspaceId(event.getWorkspaceId());
        record.setUserId(event.getUserId());
        record.setEventType(event.getEventType().name());
        record.setResourceType(event.getResourceType());
        record.setResourceId(event.getResourceId());
        record.setPayload(event.getPayload());
        record.setCreatedAt(event.getCreatedAt());
        return record;
    }

    private ActivityEvent toDomain(ActivityEventRecord record) {
        return ActivityEvent.builder()
                .id(record.getId())
                .workspaceId(record.getWorkspaceId())
                .userId(record.getUserId())
                .eventType(ActivityEventType.valueOf(record.getEventType()))
                .resourceType(record.getResourceType())
                .resourceId(record.getResourceId())
                .payload(record.getPayload())
                .createdAt(record.getCreatedAt())
                .build();
    }
}
