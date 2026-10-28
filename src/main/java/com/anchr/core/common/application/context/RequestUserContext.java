package com.anchr.core.common.application.context;

/**
 * Request-scoped user context for single-user mode.
 */
public record RequestUserContext(String userId, String role, String accessTokenHash) {

    public static final String DEFAULT_USER_ID = "system";
    public static final String DEFAULT_ROLE = "ADMIN";
    public static final String ANONYMOUS_USER_ID = "anonymous";

    /**
     * Identities that stand in for "no particular user". They are storage defaults and
     * placeholders, never real owners, so comparing them for ownership must always fail —
     * otherwise every token without a userId matches the seeded rows that also default to
     * {@code system}.
     */
    private static final java.util.Set<String> PLACEHOLDER_IDENTITIES =
            java.util.Set.of(DEFAULT_USER_ID, "guest", ANONYMOUS_USER_ID);

    /** True when this context names an actual user rather than a placeholder. */
    public boolean hasConcreteIdentity() {
        return userId != null && !userId.isBlank()
                && !PLACEHOLDER_IDENTITIES.contains(userId.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Ownership test that refuses to match on placeholder identities.
     *
     * @param ownerId the stored owner of the row being accessed
     * @return true only when both sides name the same concrete user
     */
    public boolean owns(String ownerId) {
        if (!hasConcreteIdentity() || ownerId == null || ownerId.isBlank()) {
            return false;
        }
        String normalizedOwner = ownerId.trim();
        if (PLACEHOLDER_IDENTITIES.contains(normalizedOwner.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        return userId.trim().equals(normalizedOwner);
    }
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
