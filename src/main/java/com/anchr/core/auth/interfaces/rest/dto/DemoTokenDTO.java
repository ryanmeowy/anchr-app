package com.anchr.core.auth.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Response of the demo token endpoint.
 */
@Data
@Builder
public class DemoTokenDTO {

    private String token;

    private String role;

    private String userId;

    private long expiresAt;
}
