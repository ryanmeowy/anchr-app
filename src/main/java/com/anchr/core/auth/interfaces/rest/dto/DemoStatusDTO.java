package com.anchr.core.auth.interfaces.rest.dto;

/**
 * Public demo status. Carries no secret.
 */
public record DemoStatusDTO(
        boolean enabled,
        boolean closed,
        int tokenQuota,
        String siteKey
) {
}
