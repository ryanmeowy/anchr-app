package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.anchr.core.common.constant.EmbeddingConstant;
import java.util.List;

/**
 * Vector-only search spec: KNN retrieval without additional keyword clauses.
 */
public class VectorOnlyQuerySpec implements QuerySpec {

    private final String indexName;
    private final List<Float> vector;
    private final Integer topK;
    private final Double minScore;

    public VectorOnlyQuerySpec(String indexName, List<Float> vector, Integer topK, Double minScore) {
        this.indexName = indexName;
        this.vector = vector;
        this.topK = topK;
        this.minScore = minScore;
    }

    @Override
    public SearchRequest toSearchRequest() {
        int safeTopK = topK == null || topK <= 0 ? EmbeddingConstant.DEFAULT_TOP_K : topK;
        SearchRequest.Builder builder = new SearchRequest.Builder();
        builder.index(indexName);
        builder.size(safeTopK);
        builder.sort(defaultSort());
        builder.source(s -> s.filter(f -> f.excludes("imageEmbedding")));

        if (minScore != null && minScore > 0) {
            builder.minScore(minScore);
        }

        KnnSearch knn = new KnnSearch.Builder()
                .field("imageEmbedding")
                .queryVector(vector)
                .k(safeTopK)
                .numCandidates(Math.max(EmbeddingConstant.NUM_CANDIDATES_FACTOR * safeTopK, EmbeddingConstant.DEFAULT_NUM_CANDIDATES))
                .build();
        builder.knn(knn);
        return builder.build();
    }

    private static List<SortOptions> defaultSort() {
        return List.of(
                new SortOptions.Builder().score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)).build(),
                new SortOptions.Builder().field(f -> f.field("id").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)).build()
        );
    }
}
