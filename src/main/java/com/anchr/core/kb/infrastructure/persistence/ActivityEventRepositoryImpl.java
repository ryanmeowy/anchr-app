package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.ActivityEvent;
import com.anchr.core.kb.domain.model.ActivityEventType;
import com.anchr.core.kb.domain.repository.ActivityEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MyBatis implementation of activity event repository.
 */
@Repository
@RequiredArgsConstructor
public class ActivityEventRepositoryImpl implements ActivityEventRepository {

    private final ActivityEventMapper mapper;

    @Override
    public void save(ActivityEvent event) {
        mapper.insert(toRecord(event));
    }

    @Override
    public List<ActivityEvent> listByType(String userId,
                                          ActivityEventType eventType, int limit, int offset) {
        return mapper.listByType(userId, eventType.name(), limit, offset).stream()
                .map(this::toDomain)
                .toList();
    }

    private ActivityEventRecord toRecord(ActivityEvent event) {
        ActivityEventRecord record = new ActivityEventRecord();
        record.setId(event.getId());
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
                .userId(record.getUserId())
                .eventType(ActivityEventType.valueOf(record.getEventType()))
                .resourceType(record.getResourceType())
                .resourceId(record.getResourceId())
                .payload(record.getPayload())
                .createdAt(record.getCreatedAt())
                .build();
    }
}
