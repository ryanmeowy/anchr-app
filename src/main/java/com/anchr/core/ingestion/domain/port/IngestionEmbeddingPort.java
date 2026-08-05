package com.anchr.core.ingestion.domain.port;

import java.util.List;

/**
 * Domain port for embedding capability in ingestion.
 */
public interface IngestionEmbeddingPort {

    List<Float> embed(String source, String sourceType);

    boolean isMulti();

    default EmbeddingSession openSession() {
        IngestionEmbeddingPort owner = this;
        return new EmbeddingSession() {
            @Override
            public List<Float> embed(String source, String sourceType) {
                return owner.embed(source, sourceType);
            }

            @Override
            public boolean isMulti() {
                return owner.isMulti();
            }

            @Override
            public String profileFingerprint() {
                return "legacy-profile";
            }
        };
    }

    interface EmbeddingSession {
        List<Float> embed(String source, String sourceType);

        boolean isMulti();

        String profileFingerprint();
    }

}
