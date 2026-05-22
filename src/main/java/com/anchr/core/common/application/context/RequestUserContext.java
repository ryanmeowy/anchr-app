package com.anchr.core.common.application.context;

/**
 * Request-scoped user context for Phase 4 single-user mode.
 */
public record RequestUserContext(String workspaceId, String userId) {

    public static final String DEFAULT_WORKSPACE_ID = "default";
    public static final String DEFAULT_USER_ID = "system";

    public static RequestUserContext systemDefault() {
        return new RequestUserContext(DEFAULT_WORKSPACE_ID, DEFAULT_USER_ID);
    }
}
