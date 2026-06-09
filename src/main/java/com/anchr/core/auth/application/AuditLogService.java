package com.anchr.core.auth.application;

import com.anchr.core.auth.infrastructure.persistence.AuditLogMapper;
import com.anchr.core.auth.infrastructure.persistence.AuditLogRecord;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.infrastructure.id.PrefixedIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Compliance audit log service.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final String ID_PREFIX = "audit";

    private final AuditLogMapper auditLogMapper;
    private final PrefixedIdGenerator idGenerator;

    public void record(String action, String resourceType, String resourceId, String outcome, String payload) {
        RequestUserContext context = UserContextHolder.get();
        AuditLogRecord record = new AuditLogRecord();
        record.setId(idGenerator.nextId(ID_PREFIX));
        record.setWorkspaceId(context.workspaceId());
        record.setUserId(context.userId());
        record.setAction(action);
        record.setResourceType(resourceType);
        record.setResourceId(resourceId);
        record.setOutcome(outcome);
        record.setPayload(payload == null || payload.isBlank() ? "{}" : payload);
        record.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(record);
    }

    public List<AuditLogRecord> list(String action, String resourceType, String userId,
                                     LocalDateTime from, LocalDateTime to, int limit) {
        RequestUserContext context = UserContextHolder.get();
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        return auditLogMapper.list(context.workspaceId(), action, resourceType, userId, from, to, safeLimit);
    }
}
