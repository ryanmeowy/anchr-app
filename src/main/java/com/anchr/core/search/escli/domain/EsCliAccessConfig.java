package com.anchr.core.search.escli.domain;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Access control settings for ES CLI backend endpoints.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.search.escli")
public class EsCliAccessConfig {

    /**
     * Allowed index patterns for ES CLI APIs, supports wildcard '*'.
     * If empty, defaults are inferred from vector index config.
     */
    private List<String> allowedIndexPatterns = new ArrayList<>();
}
