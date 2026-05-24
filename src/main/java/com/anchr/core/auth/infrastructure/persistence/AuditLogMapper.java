package com.anchr.core.auth.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuditLogMapper {

    int insert(AuditLogRecord record);

    List<AuditLogRecord> list(@Param("workspaceId") String workspaceId,
                              @Param("action") String action,
                              @Param("resourceType") String resourceType,
                              @Param("userId") String userId,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("limit") int limit);
}
