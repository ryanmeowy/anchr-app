package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.search.infrastructure.persistence.es.query.SimilarSearchIdMatcher;
import java.util.List;

/**
 * Similar search spec: KNN + exclude self (by id filter).
 */
public class SimilarQuerySpec implements QuerySpec {

    private final String indexName;
    private final List<Float> vector;
    private final Integer topK;
    private final String excludeDocId;
    private final SimilarSearchIdMatcher idMatcher;

    public SimilarQuerySpec(String indexName,
                              List<Float> vector,
                              Integer topK,
                              String excludeDocId,
                              SimilarSearchIdMatcher idMatcher) {
        this.indexName = indexName;
        this.vector = vector;
        this.topK = topK;
        this.excludeDocId = excludeDocId;
        this.idMatcher = idMatcher;
    }

    @Override
    public SearchRequest toSearchRequest() {
        SearchRequest.Builder builder = new SearchRequest.Builder();
        builder.index(indexName);
        builder.size(topK);
        builder.sort(defaultSort());
        builder.source(s -> s.filter(f -> f.excludes("imageEmbedding")));

        Query filterQuery = idMatcher.match(excludeDocId)
                .orElseThrow(() -> new IllegalArgumentException("excludeDocId cannot be empty"));
        builder.query(filterQuery);

        KnnSearch knn = new KnnSearch.Builder()
                .field("imageEmbedding")
                .queryVector(vector)
                .k(topK)
                .numCandidates(Math.max(EmbeddingConstant.NUM_CANDIDATES_FACTOR * topK, EmbeddingConstant.DEFAULT_NUM_CANDIDATES))
                .filter(filterQuery)
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
