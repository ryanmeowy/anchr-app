package com.anchr.core.ingestion.domain.port;

import java.util.List;

/**
 * Domain port for embedding capability in ingestion.
 */
public interface IngestionEmbeddingPort {

    List<Float> embed(String source, String sourceType);

}
