package com.anchr.core.ingestion.domain.port;

import com.anchr.core.common.model.GraphTriple;

import java.util.List;

/**
 * Domain port for AI content enrichments used by ingestion.
 */
public interface IngestionContentPort {

    /**
     * Generate semantic file name.
     */
    String generateFileName(String imageInput);

    /**
     * Generate tags for image.
     */
    List<String> generateTags(String imageInput);

    /**
     * Generate graph triples for image.
     */
    List<GraphTriple> generateGraph(String imageInput);
}
