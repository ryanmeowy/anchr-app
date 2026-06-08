package com.anchr.core.auth.application;

import com.anchr.core.auth.domain.model.WorkspaceRole;
import com.anchr.core.auth.infrastructure.persistence.UserAccountMapper;
import com.anchr.core.auth.infrastructure.persistence.UserAccountRecord;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceMapper;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceMemberRecord;
import com.anchr.core.auth.infrastructure.persistence.WorkspaceRecord;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.common.infrastructure.id.PrefixedIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Minimal OIDC identity mapping. Signature verification should be added before production SSO rollout.
 */
@Service
public class OidcSsoService {

    private static final String USER_PREFIX = "user";
    private static final String DEFAULT_WORKSPACE_ID = "default";

    private final UserAccountMapper userAccountMapper;
    private final WorkspaceMapper workspaceMapper;
    private final SessionTokenService sessionTokenService;
    private final AuditLogService auditLogService;
    private final PrefixedIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final String authorizationUrl;
    private final String issuer;

    public OidcSsoService(UserAccountMapper userAccountMapper,
                          WorkspaceMapper workspaceMapper,
                          SessionTokenService sessionTokenService,
                          AuditLogService auditLogService,
                          PrefixedIdGenerator idGenerator,
                          ObjectMapper objectMapper,
                          @org.springframework.beans.factory.annotation.Value("${app.sso.oidc.authorization-url:}") String authorizationUrl,
                          @org.springframework.beans.factory.annotation.Value("${app.sso.oidc.issuer:}") String issuer) {
        this.userAccountMapper = userAccountMapper;
        this.workspaceMapper = workspaceMapper;
        this.sessionTokenService = sessionTokenService;
        this.auditLogService = auditLogService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.authorizationUrl = authorizationUrl;
        this.issuer = issuer;
    }

    public String loginUrl() {
        if (!StringUtils.hasText(authorizationUrl)) {
            throw new BusinessException(ApiError.PROVIDER_UNAVAILABLE, "OIDC provider is not configured.");
        }
        return authorizationUrl;
    }

    @Transactional
    public SsoLoginResult callback(String idToken) {
        try {
            JsonNode claims = decodeClaims(idToken);
            String tokenIssuer = claims.path("iss").asText(issuer);
            String subject = claims.path("sub").asText();
            if (!StringUtils.hasText(tokenIssuer) || !StringUtils.hasText(subject)) {
                throw new BusinessException(ApiError.INVALID_REQUEST, "OIDC id_token must contain iss and sub.");
            }
            UserAccountRecord user = userAccountMapper.findByExternal(tokenIssuer, subject)
                    .orElseGet(() -> createExternalUser(tokenIssuer, subject, claims));
            WorkspaceMemberRecord member = workspaceMapper.findMember(DEFAULT_WORKSPACE_ID, user.getId())
                    .orElseGet(() -> addDefaultMember(user.getId()));
            String token = sessionTokenService.create(SessionTokenService.SessionPrincipal.builder()
                    .userId(user.getId())
                    .email(user.getEmail())
                    .displayName(user.getDisplayName())
                    .workspaceId(member.getWorkspaceId())
                    .role(member.getRole())
                    .build());
            auditLogService.record("LOGIN", "USER", user.getId(), "SUCCESS", "{\"sso\":\"oidc\"}");
            return SsoLoginResult.builder()
                    .token(token)
                    .userId(user.getId())
                    .email(user.getEmail())
                    .displayName(user.getDisplayName())
                    .workspaceId(member.getWorkspaceId())
                    .role(member.getRole())
                    .build();
        } catch (BusinessException e) {
            auditLogService.record("SSO_LOGIN_FAILED", "USER", "", "FAILED", "{}");
            throw e;
        } catch (Exception e) {
            auditLogService.record("SSO_LOGIN_FAILED", "USER", "", "FAILED", "{}");
            throw new BusinessException(ApiError.INVALID_REQUEST, "Failed to parse OIDC id_token.", e);
        }
    }

    private JsonNode decodeClaims(String idToken) throws Exception {
        if (!StringUtils.hasText(idToken)) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "idToken cannot be blank.");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new BusinessException(ApiError.INVALID_REQUEST, "Invalid OIDC id_token.");
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
    }

    private UserAccountRecord createExternalUser(String issuer, String subject, JsonNode claims) {
        LocalDateTime now = LocalDateTime.now();
        String email = claims.path("email").asText(subject + "@oidc.local").toLowerCase();
        var existing = userAccountMapper.findByEmail(email);
        if (existing.isPresent()) {
            userAccountMapper.bindExternal(existing.get().getId(), issuer, subject);
            existing.get().setExternalIssuer(issuer);
            existing.get().setExternalSubject(subject);
            return existing.get();
        }
        UserAccountRecord user = new UserAccountRecord();
        user.setId(idGenerator.nextId(USER_PREFIX));
        user.setEmail(email);
        user.setDisplayName(claims.path("name").asText(email));
        user.setStatus("ACTIVE");
        user.setExternalIssuer(issuer);
        user.setExternalSubject(subject);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userAccountMapper.insert(user);
        return user;
    }

    private WorkspaceMemberRecord addDefaultMember(String userId) {
        LocalDateTime now = LocalDateTime.now();
        WorkspaceRecord workspace = new WorkspaceRecord();
        workspace.setId(DEFAULT_WORKSPACE_ID);
        workspace.setName("Default Workspace");
        workspace.setStatus("ACTIVE");
        workspace.setCreatedBy(userId);
        workspace.setUpdatedBy(userId);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        try {
            workspaceMapper.insertWorkspace(workspace);
        } catch (Exception ignored) {
            // Default workspace may already exist.
        }
        WorkspaceMemberRecord member = new WorkspaceMemberRecord();
        member.setWorkspaceId(DEFAULT_WORKSPACE_ID);
        member.setUserId(userId);
        member.setRole(WorkspaceRole.VIEWER.name());
        member.setStatus("ACTIVE");
        member.setCreatedBy(userId);
        member.setUpdatedBy(userId);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        workspaceMapper.insertMember(member);
        return member;
    }

    @Value
    @Builder
    public static class SsoLoginResult {
        String token;
        String userId;
        String email;
        String displayName;
        String workspaceId;
        String role;
    }
}
