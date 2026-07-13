package com.anchr.core.search.application;

import java.util.List;
import java.util.Map;

/**
 * Generates user-facing explanations for the final answer citations in one batch.
 */
public interface CitationReasonGenerationService {

    Map<String, String> generate(Request request);

    record Request(String question,
                   String rewrittenQuery,
                   String answer,
                   List<CitationGroup> citations) {
    }

    record CitationGroup(Integer citationIndex,
                         String assetId,
                         List<CitationChunk> chunks) {
    }

    record CitationChunk(String segmentId,
                         String content,
                         Double score,
                         List<String> hitSources,
                         String matchSummary) {
    }
}
