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

    private final Rrf rrf = new Rrf();
    private final Rerank rerank = new Rerank();
    private final VectorRoutes vectorRoutes = new VectorRoutes();

    @Data
    public static class Rrf {
        private int rankConstant = 60;
        private int candidateMultiplier = 4;
        private int maxCandidates = 200;
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

    @Data
    public static class VectorRoutes {
        private int textTopK = 80;
        private int documentImageTopK = 40;
        private float textSimilarity = 0.75f;
        private float documentImageSimilarity = 0.70f;
    }
}
