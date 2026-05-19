package com.smart.vision.core.search.domain.port;

import com.smart.vision.core.common.model.GraphTriple;

import java.util.List;

/**
 * Domain port for content enrichment capability used by search.
 */
public interface SearchContentPort {

    /**
     * Generate summary for image input.
     */
    String generateSummary(String imageInput);

    /**
     * Generate tags for image input.
     */
    List<String> generateTags(String imageInput);

    /**
     * Generate graph triples for image input.
     */
    List<GraphTriple> generateGraph(String imageInput);
}
