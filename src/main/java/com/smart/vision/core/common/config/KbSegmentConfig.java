package com.smart.vision.core.common.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Config for unified kb_segment index.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.kb-segment")
public class KbSegmentConfig {

    private String indexName = "kb_segment";
    private Integer dimension;
    private String readAlias;
    private String writeAlias;
    private String indexVersion;
    @Value("${app.capability-provider.embedding:unknown}")
    private String embeddingProvider;
    @Value("${app.embedding.model:unknown}")
    private String embeddingModel;
    @Value("${app.embedding.preprocess-version:v1}")
    private String preprocessVersion;
    @Value("${app.vector.dimension:0}")
    private Integer fallbackVectorDimension;

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

    public Integer getResolvedDimension() {
        if (dimension != null && dimension > 0) {
            return dimension;
        }
        return fallbackVectorDimension;
    }

    public String getVectorProfile() {
        int dims = getResolvedDimension() == null ? 0 : getResolvedDimension();
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
