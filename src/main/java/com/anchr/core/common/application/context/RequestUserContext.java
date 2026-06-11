package com.anchr.core.common.application.context;

/**
 * Request-scoped user context for single-user mode.
 */
public record RequestUserContext(String userId, String role) {

    public static final String DEFAULT_USER_ID = "system";
    public static final String DEFAULT_ROLE = "OWNER";

    public static RequestUserContext systemDefault() {
        return new RequestUserContext(DEFAULT_USER_ID, DEFAULT_ROLE);
    }
}
