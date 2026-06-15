package com.anchr.core.search.config;

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
    private Integer dimension;
    private String readAlias;
    private String writeAlias;
    private String indexVersion;

    public String getPhysicalIndexName() {
        if (indexVersion == null || indexVersion.isBlank()) {
            return indexName;
        } else {
            return indexName + "_" + indexVersion;
        }
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
