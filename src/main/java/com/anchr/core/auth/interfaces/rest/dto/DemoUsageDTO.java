package com.anchr.core.auth.interfaces.rest.dto;

/**
 * Usage of the caller's own demo token. Carries no secret and no visitor identity.
 *
 * @param quota      credits granted to one token
 * @param used       credits already charged
 * @param remaining  credits left; never negative
 * @param expiresAt  epoch millis when the token stops working
 */
public record DemoUsageDTO(
        int quota,
        int used,
        int remaining,
        long expiresAt
) {
}
