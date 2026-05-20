package com.anchr.core.search.domain.model;

import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Image search result model
 *
 * @author Ryan
 * @since 2025/12/23
 */
@Data
@Builder
public class ImageSearchResultDTO {
    /**
     * The image document containing metadata and content information
     */
    private ImageDocument document;

    /**
     * Display score for API output.
     */
    private Double score;

    /**
     * Raw retrieval score from Elasticsearch. Used for ranking/filter decisions.
     */
    private Double rawScore;

    /**
     * Search cursor for pagination
     */
    private List<Object> sortValues;

    /**
     * Elasticsearch field-level highlights for explainability.
     */
    private Map<String, String> highlights;

    /**
     * Highlighted ES field names that produced the snippet, e.g. ocrContent/tags/fileName.
     */
    private List<String> highlightFields;

    /**
     * Whether this document appears in vector recall set in hybrid retrieval.
     * Null means unknown (not annotated by strategy).
     */
    private Boolean vectorRecallHit;

    /**
     * Whether this document appears in text recall set in hybrid retrieval.
     * Null means unknown (not annotated by strategy).
     */
    private Boolean textRecallHit;
}
  
