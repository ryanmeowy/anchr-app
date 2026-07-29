package com.anchr.core.activity.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis mapper for activity_event.
 */
@Mapper
public interface ActivityEventMapper {

    int insert(ActivityEventRecord record);

    List<ActivityEventRecord> listByType(@Param("userId") String userId,
                                         @Param("eventType") String eventType,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset,
                                         @Param("since") LocalDateTime since);

    ActivityEventRecord searchById(@Param("id") String id,
                                   @Param("eventType") String eventType);

    int deleteBySessionId(@Param("sessionId") String sessionId);

    int deleteCitationOpenedByAssetId(@Param("userId") String userId,
                                      @Param("assetId") String assetId);
}
