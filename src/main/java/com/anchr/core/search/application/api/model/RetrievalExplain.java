package com.anchr.core.search.application.api.model;

import java.util.List;

public record RetrievalExplain(
        List<String> hitSources,
        MatchedBy matchedBy,
        TextSignals textSignals,
        ImageSignals imageSignals
) {
    public RetrievalExplain {
        hitSources = hitSources == null ? List.of() : List.copyOf(hitSources);
    }

    public record MatchedBy(boolean vector, boolean title, boolean content, boolean ocr) {
    }

    public record TextSignals(boolean semantic, boolean keyword, boolean pageHit, boolean chunkHit) {
    }

    public record ImageSignals(boolean vector, boolean ocr, boolean caption, boolean tag) {
    }
}
