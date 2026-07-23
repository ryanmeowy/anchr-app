package com.anchr.core.ingestion.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Safety limits for immutable ingestion artifacts stored outside the database.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.ingestion.artifact")
public class IngestionArtifactProperties {

    private int maxCompressedBytes = 32 * 1024 * 1024;
    private int maxUncompressedBytes = 256 * 1024 * 1024;
}
