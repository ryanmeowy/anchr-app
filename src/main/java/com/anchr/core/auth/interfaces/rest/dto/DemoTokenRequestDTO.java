package com.anchr.core.auth.interfaces.rest.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body of the demo token endpoint.
 */
@Data
public class DemoTokenRequestDTO {

    /**
     * Alibaba Cloud CAPTCHA 2.0 result parameter produced by the client SDK. Optional only while
     * verification is disabled.
     */
    @Size(max = 8192)
    private String captchaVerifyParam;
}
