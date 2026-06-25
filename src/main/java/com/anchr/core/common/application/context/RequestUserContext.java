package com.anchr.core.common.application.context;

/**
 * Request-scoped user context for single-user mode.
 */
public record RequestUserContext(String userId, String role, String accessTokenHash) {

    public static final String DEFAULT_USER_ID = "system";
    public static final String DEFAULT_ROLE = "OWNER";

    public RequestUserContext(String userId, String role) {
        this(userId, role, null);
    }

    public static RequestUserContext systemDefault() {
        return new RequestUserContext(DEFAULT_USER_ID, DEFAULT_ROLE, null);
    }

    public static RequestUserContext systemDefault(String accessTokenHash) {
        return new RequestUserContext(DEFAULT_USER_ID, DEFAULT_ROLE, accessTokenHash);
    }
}
