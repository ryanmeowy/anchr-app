package com.smart.vision.core.common.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
