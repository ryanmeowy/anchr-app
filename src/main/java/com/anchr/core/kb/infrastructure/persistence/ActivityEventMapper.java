package com.anchr.core.kb.infrastructure.persistence;

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
}
