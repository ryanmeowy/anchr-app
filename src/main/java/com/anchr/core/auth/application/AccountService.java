package com.anchr.core.auth.application;

import com.anchr.core.auth.domain.model.WorkspaceRole;
import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.auth.infrastructure.persistence.UserAccountMapper;
import com.anchr.core.auth.infrastructure.persistence.UserAccountRecord;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceMapper;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceMemberRecord;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceRecord;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.infrastructure.id.PrefixedIdGenerator;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Local account login service.
 */
@Service
public class AccountService {

    private static final String USER_PREFIX = "user";
    private static final String WORKSPACE_PREFIX = "ws";
    private static final String DEFAULT_WORKSPACE_ID = "default";

    private final UserAccountMapper userAccountMapper;
    private final WorkspaceMapper workspaceMapper;
    private final PasswordHashService passwordHashService;
    private final SessionTokenService sessionTokenService;
    private final PermissionService permissionService;
    private final PrefixedIdGenerator idGenerator;

    public AccountService(UserAccountMapper userAccountMapper,
                          WorkspaceMapper workspaceMapper,
                          PasswordHashService passwordHashService,
                          SessionTokenService sessionTokenService,
                          PermissionService permissionService,
                          PrefixedIdGenerator idGenerator) {
        this.userAccountMapper = userAccountMapper;
        this.workspaceMapper = workspaceMapper;
        this.passwordHashService = passwordHashService;
        this.sessionTokenService = sessionTokenService;
        this.permissionService = permissionService;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public LoginResult login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        UserAccountRecord user = userAccountMapper.findByEmail(normalizedEmail)
                .orElseGet(() -> createFirstUserIfAllowed(normalizedEmail, password));
        if (!passwordHashService.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ApiError.UNAUTHORIZED, "Invalid email or password.");
        }
        WorkspaceMemberRecord member = workspaceMapper.findMember(DEFAULT_WORKSPACE_ID, user.getId())
                .orElseThrow(() -> new BusinessException(ApiError.FORBIDDEN, "User is not a workspace member."));
        String token = sessionTokenService.create(SessionTokenService.SessionPrincipal.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .workspaceId(member.getWorkspaceId())
                .role(member.getRole())
                .build());
        return LoginResult.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .workspaceId(member.getWorkspaceId())
                .role(member.getRole())
                .build();
    }

    public Optional<SessionTokenService.SessionPrincipal> me(String token) {
        return sessionTokenService.resolve(token);
    }

    public void logout(String token) {
        sessionTokenService.revoke(token);
    }

    @Transactional
    public LoginResult createUser(String email, String password, String displayName, WorkspaceRole role) {
        permissionService.requireManageMembers();
        RequestUserContext context = UserContextHolder.get();
        String normalizedEmail = normalizeEmail(email);
        if (userAccountMapper.findByEmail(normalizedEmail).isPresent()) {
            throw new BusinessException(ApiError.CONFLICT, "User email already exists.");
        }
        LocalDateTime now = LocalDateTime.now();
        UserAccountRecord user = new UserAccountRecord();
        user.setId(idGenerator.nextId(USER_PREFIX));
        user.setEmail(normalizedEmail);
        user.setDisplayName(displayName == null || displayName.isBlank() ? normalizedEmail : displayName.trim());
        user.setPasswordHash(passwordHashService.hash(password));
        user.setStatus("ACTIVE");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userAccountMapper.insert(user);

        WorkspaceMemberRecord member = new WorkspaceMemberRecord();
        member.setWorkspaceId(context.workspaceId());
        member.setUserId(user.getId());
        member.setRole((role == null ? WorkspaceRole.VIEWER : role).name());
        member.setStatus("ACTIVE");
        member.setCreatedBy(context.userId());
        member.setUpdatedBy(context.userId());
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        workspaceMapper.insertMember(member);
        return LoginResult.builder()
                .token(null)
                .userId(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .workspaceId(context.workspaceId())
                .role(member.getRole())
                .build();
    }

    private UserAccountRecord createFirstUserIfAllowed(String email, String password) {
        if (userAccountMapper.count() > 0) {
            throw new BusinessException(ApiError.UNAUTHORIZED, "Invalid email or password.");
        }
        LocalDateTime now = LocalDateTime.now();
        UserAccountRecord user = new UserAccountRecord();
        user.setId(idGenerator.nextId(USER_PREFIX));
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordHashService.hash(password));
        user.setStatus("ACTIVE");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userAccountMapper.insert(user);

        WorkspaceRecord workspace = new WorkspaceRecord();
        workspace.setId(DEFAULT_WORKSPACE_ID);
        workspace.setName("Default Workspace");
        workspace.setStatus("ACTIVE");
        workspace.setCreatedBy(user.getId());
        workspace.setUpdatedBy(user.getId());
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        try {
            workspaceMapper.insertWorkspace(workspace);
        } catch (Exception ignored) {
            // Default workspace may already exist from a previous bootstrap.
        }

        WorkspaceMemberRecord member = new WorkspaceMemberRecord();
        member.setWorkspaceId(DEFAULT_WORKSPACE_ID);
        member.setUserId(user.getId());
        member.setRole(WorkspaceRole.OWNER.name());
        member.setStatus("ACTIVE");
        member.setCreatedBy(user.getId());
        member.setUpdatedBy(user.getId());
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        workspaceMapper.insertMember(member);
        return user;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "email cannot be blank.");
        }
        return email.trim().toLowerCase();
    }

    @Value
    @Builder
    public static class LoginResult {
        String token;
        String userId;
        String email;
        String displayName;
        String workspaceId;
        String role;
    }
}
