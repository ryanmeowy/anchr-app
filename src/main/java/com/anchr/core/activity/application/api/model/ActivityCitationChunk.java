package com.anchr.core.activity.application.api.model;

import java.util.List;

/** Activity-owned immutable citation chunk snapshot. */
public record ActivityCitationChunk(String segmentId, Integer segmentIndex, String citationLabel,
                                    String title, Integer pageNo, Integer chunkOrder, String content,
                                    String snippet, String hitType, ActivityAnchor anchor, Why why) {

    public record Why(Double score, List<String> hitSources, MatchedBy matchedBy,
                      String matchSummary, String reason) {
        public Why {
            hitSources = hitSources == null || hitSources.isEmpty() ? List.of() : List.copyOf(hitSources);
        }
    }

    public record MatchedBy(Boolean vector, Boolean title, Boolean content, Boolean ocr) {
    }
}
