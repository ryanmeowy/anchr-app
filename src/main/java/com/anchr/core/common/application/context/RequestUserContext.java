package com.anchr.core.common.application.context;

/**
 * Request-scoped user context for single-user mode.
 */
public record RequestUserContext(String userId, String role, String accessTokenHash) {

    public static final String DEFAULT_USER_ID = "system";
    public static final String DEFAULT_ROLE = "ADMIN";
    public static final String ANONYMOUS_USER_ID = "anonymous";
    public static final String ANONYMOUS_ROLE = "ANONYMOUS";

    public RequestUserContext(String userId, String role) {
        this(userId, role, null);
    }

    public static RequestUserContext systemDefault() {
        return new RequestUserContext(DEFAULT_USER_ID, DEFAULT_ROLE, null);
    }

    public static RequestUserContext anonymous() {
        return new RequestUserContext(ANONYMOUS_USER_ID, ANONYMOUS_ROLE, null);
    }
}
