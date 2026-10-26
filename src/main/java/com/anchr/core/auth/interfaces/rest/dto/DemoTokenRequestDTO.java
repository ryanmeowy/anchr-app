package com.anchr.core.auth.interfaces.rest.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body of the demo token endpoint.
 */
@Data
public class DemoTokenRequestDTO {

    /**
     * Cloudflare Turnstile response token. Optional only while verification is disabled.
     */
    @Size(max = 4096)
    private String turnstileToken;
}
