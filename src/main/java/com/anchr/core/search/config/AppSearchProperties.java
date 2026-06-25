package com.anchr.core.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Unified search module properties.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.search")
public class AppSearchProperties {

    private double qualityAbsoluteMinScore = 0.72d;
    private double vectorMinScore = 0.6d;
    private final Page page = new Page();
    private final Rrf rrf = new Rrf();
    private final Rerank rerank = new Rerank();

    @Data
    public static class Page {
        private int defaultPageSize = 10;
        private int maxWindow = 100;
        private long sessionTtlSeconds = 900L;
        private String cursorSecret = "";
    }

    @Data
    public static class Rrf {
        private boolean enabled = true;
        private int rankConstant = 60;
        private int candidateMultiplier = 4;
        private int maxCandidates = 200;
        private boolean nativeEnabled = false;
    }

    @Data
    public static class Rerank {
        private int maxDocChars = 1200;
        private boolean windowEnabled = true;
        private int windowSize = 40;
        private int windowFactor = 3;
        private int windowMin = 20;
        private int windowMax = 80;
        private double fusionAlpha = 0.6d;
        private double fusionBeta = 0.4d;
    }
}
