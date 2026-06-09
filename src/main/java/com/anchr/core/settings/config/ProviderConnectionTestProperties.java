package com.anchr.core.settings.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Test inputs used by real provider connection checks.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.settings.connection-test")
public class ProviderConnectionTestProperties {
    private String generationPrompt = "";
    private String embeddingText = "";
    private String rerankQuery = "";
    private String rerankDocument = "";
    private String ocrImageUrl = "";
    private String objectStorageObjectKey = "";
}
