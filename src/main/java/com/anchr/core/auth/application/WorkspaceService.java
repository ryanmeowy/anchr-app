package com.anchr.core.auth.application;

import com.anchr.core.auth.domain.model.WorkspaceRole;
import com.anchr.core.auth.infrastructure.persistence.UserAccountMapper;
import com.anchr.core.auth.infrastructure.persistence.UserAccountRecord;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceMapper;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceMemberRecord;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceRecord;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.infrastructure.id.PrefixedIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Workspace and member application service.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private static final String WORKSPACE_PREFIX = "ws";

    private final WorkspaceMapper workspaceMapper;
    private final UserAccountMapper userAccountMapper;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;
    private final PrefixedIdGenerator idGenerator;

    public List<WorkspaceRecord> list() {
        return workspaceMapper.listByUser(UserContextHolder.get().userId());
    }

    @Transactional
    public WorkspaceRecord create(String name) {
        permissionService.requireManageMembers();
        RequestUserContext context = UserContextHolder.get();
        LocalDateTime now = LocalDateTime.now();
        WorkspaceRecord workspace = new WorkspaceRecord();
        workspace.setId(idGenerator.nextId(WORKSPACE_PREFIX));
        workspace.setName(requireText(name, "name"));
        workspace.setStatus("ACTIVE");
        workspace.setCreatedBy(context.userId());
        workspace.setUpdatedBy(context.userId());
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspaceMapper.insertWorkspace(workspace);
        addMemberRecord(workspace.getId(), context.userId(), WorkspaceRole.OWNER.name(), context.userId(), now);
        auditLogService.record("WORKSPACE_CREATED", "WORKSPACE", workspace.getId(), "SUCCESS", "{}");
        return workspace;
    }

    public List<WorkspaceMemberRecord> listMembers(String workspaceId) {
        permissionService.requireManageMembers();
        return workspaceMapper.listMembers(requireText(workspaceId, "workspaceId"));
    }

    @Transactional
    public WorkspaceMemberRecord addMember(String workspaceId, String email, WorkspaceRole role) {
        permissionService.requireManageMembers();
        RequestUserContext context = UserContextHolder.get();
        UserAccountRecord user = userAccountMapper.findByEmail(requireText(email, "email").toLowerCase())
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND, "User account not found."));
        LocalDateTime now = LocalDateTime.now();
        addMemberRecord(requireText(workspaceId, "workspaceId"), user.getId(), safeRole(role).name(), context.userId(), now);
        auditLogService.record("MEMBER_UPDATED", "WORKSPACE", workspaceId, "SUCCESS",
                "{\"userId\":\"" + user.getId() + "\"}");
        return workspaceMapper.findMember(workspaceId, user.getId())
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND, "Workspace member not found."));
    }

    @Transactional
    public WorkspaceMemberRecord updateRole(String workspaceId, String userId, WorkspaceRole role) {
        permissionService.requireManageMembers();
        RequestUserContext context = UserContextHolder.get();
        int updated = workspaceMapper.updateMemberRole(requireText(workspaceId, "workspaceId"),
                requireText(userId, "userId"), safeRole(role).name(), context.userId(), LocalDateTime.now());
        if (updated == 0) {
            throw new BusinessException(ApiError.NOT_FOUND, "Workspace member not found.");
        }
        auditLogService.record("MEMBER_UPDATED", "WORKSPACE", workspaceId, "SUCCESS",
                "{\"userId\":\"" + userId + "\"}");
        return workspaceMapper.findMember(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(ApiError.NOT_FOUND, "Workspace member not found."));
    }

    @Transactional
    public void removeMember(String workspaceId, String userId) {
        permissionService.requireManageMembers();
        RequestUserContext context = UserContextHolder.get();
        int updated = workspaceMapper.removeMember(requireText(workspaceId, "workspaceId"),
                requireText(userId, "userId"), context.userId(), LocalDateTime.now());
        if (updated == 0) {
            throw new BusinessException(ApiError.NOT_FOUND, "Workspace member not found.");
        }
        auditLogService.record("MEMBER_UPDATED", "WORKSPACE", workspaceId, "SUCCESS",
                "{\"userId\":\"" + userId + "\"}");
    }

    private void addMemberRecord(String workspaceId, String userId, String role, String actorId, LocalDateTime now) {
        WorkspaceMemberRecord member = new WorkspaceMemberRecord();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus("ACTIVE");
        member.setCreatedBy(actorId);
        member.setUpdatedBy(actorId);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        workspaceMapper.insertMember(member);
    }

    private WorkspaceRole safeRole(WorkspaceRole role) {
        return role == null ? WorkspaceRole.VIEWER : role;
    }

    private String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, field + " cannot be blank.");
        }
        return value.trim();
    }
}
