package com.anchr.core.settings.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Startup defaults for provider selection.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.capability-provider")
public class CapabilityProviderProperties {
    private String gen = "aliyun";
    private String rerank = "aliyun";
    private String ocr = "aliyun";
    private String objectStorage = "aliyun";
}
