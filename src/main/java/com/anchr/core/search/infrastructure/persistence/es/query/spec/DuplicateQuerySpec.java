package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.anchr.core.common.constant.EmbeddingConstant;

import java.util.List;

/**
 * Duplicate check spec: top1 KNN with similarity threshold.
 */
public class DuplicateQuerySpec implements QuerySpec {

    private final String indexName;
    private final List<Float> vector;
    private final double threshold;

    public DuplicateQuerySpec(String indexName, List<Float> vector, double threshold) {
        this.indexName = indexName;
        this.vector = vector;
        this.threshold = threshold;
    }

    @Override
    public SearchRequest toSearchRequest() {
        SearchRequest.Builder builder = new SearchRequest.Builder();
        builder.index(indexName);
        builder.size(1);
        builder.source(s -> s.filter(f -> f.excludes("imageEmbedding")));

        float similarity = (float) threshold;
        KnnSearch knn = new KnnSearch.Builder()
                .field("imageEmbedding")
                .queryVector(vector)
                .k(1)
                .numCandidates(EmbeddingConstant.DEFAULT_NUM_CANDIDATES)
                .similarity(similarity)
                .build();
        builder.knn(knn);

        return builder.build();
    }
}

