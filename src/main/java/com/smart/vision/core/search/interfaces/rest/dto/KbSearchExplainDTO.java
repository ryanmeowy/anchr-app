package com.smart.vision.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * Explain payload for kb search result.
 */
@Data
@Builder
public class KbSearchExplainDTO implements Serializable {

    /**
     * Effective strategy marker for diagnostics.
     */
    private String strategyEffective;

    /**
     * Coarse match sources.
     */
    private List<String> hitSources;

    /**
     * Field-level match flags.
     */
    private MatchedBy matchedBy;

    /**
     * Text explain v2 signals.
     */
    private TextSignals textSignals;

    /**
     * Image explain v2 signals.
     */
    private ImageSignals imageSignals;

    @Data
    @Builder
    public static class MatchedBy implements Serializable {
        private boolean vector;
        private boolean title;
        private boolean content;
        private boolean ocr;
    }

    @Data
    @Builder
    public static class TextSignals implements Serializable {
        private boolean semantic;
        private boolean keyword;
        private boolean pageHit;
        private boolean chunkHit;
    }

    @Data
    @Builder
    public static class ImageSignals implements Serializable {
        private boolean vector;
        private boolean ocr;
        private boolean caption;
        private boolean tag;
    }
}
