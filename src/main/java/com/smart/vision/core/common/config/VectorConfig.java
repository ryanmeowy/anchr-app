package com.smart.vision.core.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.vector")
public class VectorConfig {
    private String indexName;
    private Integer dimension;
    /**
     * Optional read alias. If blank, reads hit physical index directly.
     */
    private String readAlias;
    /**
     * Optional write alias. If blank, writes hit physical index directly.
     */
    private String writeAlias;

    /**
     * Physical index version suffix (e.g. v1/v2).
     * <p>
     * Physical index name = indexName + "_" + indexVersion (when indexVersion is not blank).
     */
    private String indexVersion;
    @Value("${app.capability-provider.embedding:unknown}")
    private String embeddingProvider;
    @Value("${app.embedding.model:unknown}")
    private String embeddingModel;
    @Value("${app.embedding.preprocess-version:v1}")
    private String preprocessVersion;

    public String getPhysicalIndexName() {
        String base;
        if (indexVersion == null || indexVersion.isBlank()) {
            base = indexName;
        } else {
            base = indexName + "_" + indexVersion;
        }
        return appendVectorProfile(base);
    }

    public String getReadTargetName() {
        if (readAlias == null || readAlias.isBlank()) {
            return getPhysicalIndexName();
        }
        return readAlias;
    }

    public String getWriteTargetName() {
        if (writeAlias == null || writeAlias.isBlank()) {
            return getPhysicalIndexName();
        }
        return writeAlias;
    }

    public String getVectorProfile() {
        int dims = dimension == null ? 0 : dimension;
        return sanitize(embeddingProvider) + "-" + sanitize(embeddingModel) + "-" + dims + "-" + sanitize(preprocessVersion);
    }

    private String appendVectorProfile(String baseName) {
        String suffix = "__" + getVectorProfile();
        if (!StringUtils.hasText(baseName)) {
            return suffix;
        }
        if (baseName.endsWith(suffix)) {
            return baseName;
        }
        return baseName + suffix;
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "na";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
    }
}
