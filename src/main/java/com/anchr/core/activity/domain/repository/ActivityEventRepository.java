package com.anchr.core.activity.domain.repository;

import com.anchr.core.activity.domain.model.ActivityEvent;
import com.anchr.core.activity.domain.model.ActivityEventType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository boundary for activity_event persistence.
 */
public interface ActivityEventRepository {

    void save(ActivityEvent event);

    List<ActivityEvent> listByType(String userId, ActivityEventType eventType, int limit, int offset,
                                   LocalDateTime since);

    ActivityEvent fetchByIdAndType(String id, ActivityEventType eventType);

    void deleteBySessionId(String sessionId);

    void deleteCitationOpenedByAssetId(String userId, String assetId);
}
