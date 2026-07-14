package com.anchr.core.auth.interfaces.rest.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Result returned after validating an access token.
 */
@Value
@Builder
public class TokenValidationDTO {
    boolean valid;
    String role;
}
