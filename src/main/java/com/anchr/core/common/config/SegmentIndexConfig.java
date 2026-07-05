package com.anchr.core.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Config for unified kb_segment index.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.segment")
public class SegmentIndexConfig {

    private String indexName = "kb_segment";
    private String readAlias;
    private String writeAlias;

    public String getPhysicalIndexName() {
        return indexName;
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
}
