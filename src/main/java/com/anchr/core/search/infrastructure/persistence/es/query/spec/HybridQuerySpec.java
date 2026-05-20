package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import cn.hutool.core.collection.CollectionUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.search.domain.model.HybridSearchParamDTO;
import com.anchr.core.search.infrastructure.persistence.es.query.GraphTriplesMatcher;
import com.anchr.core.search.infrastructure.persistence.es.query.HybridSearchKeywordMatcher;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Hybrid (KNN + keyword + optional graph) SearchRequest spec.
 */
@Deprecated(since = "2026-04", forRemoval = false)
public class HybridQuerySpec implements QuerySpec {

    private final String indexName;
    private final HybridSearchParamDTO paramDTO;
    private final HybridSearchKeywordMatcher keywordMatcher;
    private final GraphTriplesMatcher graphTriplesMatcher;

    public HybridQuerySpec(String indexName,
                             HybridSearchParamDTO paramDTO,
                             HybridSearchKeywordMatcher keywordMatcher,
                             GraphTriplesMatcher graphTriplesMatcher) {
        this.indexName = indexName;
        this.paramDTO = paramDTO;
        this.keywordMatcher = keywordMatcher;
        this.graphTriplesMatcher = graphTriplesMatcher;
    }

    @Override
    public SearchRequest toSearchRequest() {
        SearchRequest.Builder builder = new SearchRequest.Builder();
        builder.index(indexName);
        builder.size(paramDTO.getLimit());
        builder.source(s -> s.filter(f -> f.excludes("imageEmbedding")));

        if (CollectionUtil.isNotEmpty(paramDTO.getSearchAfter())) {
            builder.searchAfter(paramDTO.getSearchAfter().stream().map(FieldValue::of).collect(Collectors.toList()));
        }

        builder.sort(defaultSort());

        // 1) KNN section
        KnnSearch knnSearch = buildHybridKnnSearch();
        builder.knn(knnSearch);

        // 2) Main query section (keyword + graph)
        Query merged = buildMainQuery();
        if (merged != null) {
            builder.query(merged);
        }
        if (hasKeyword()) {
            builder.highlight(buildHighlight(isEnableOcrEnabled()));
        }
        return builder.build();
    }

    private KnnSearch buildHybridKnnSearch() {
        // topK/limit are populated by HybridRetrievalStrategy
        int topK = Optional.ofNullable(paramDTO.getTopK()).orElse(EmbeddingConstant.DEFAULT_TOP_K);
        int numCandidates = Math.max(EmbeddingConstant.DEFAULT_NUM_CANDIDATES, topK * EmbeddingConstant.NUM_CANDIDATES_FACTOR);

        return new KnnSearch.Builder()
                .field("imageEmbedding")
                .queryVector(paramDTO.getQueryVector())
                .k(topK)
                .boost(EmbeddingConstant.DEFAULT_EMBEDDING_BOOST)
                .numCandidates(numCandidates)
                .build();
    }

    private Query buildMainQuery() {
        BoolQuery.Builder combined = new BoolQuery.Builder();
        boolean hasClause = false;

        if (hasKeyword()) {
            Optional<Query> keywordQueryOpt = keywordMatcher.match(paramDTO.getKeyword(), isEnableOcrEnabled());
            if (keywordQueryOpt.isPresent()) {
                combined.should(keywordQueryOpt.get());
                hasClause = true;
            }
        }

        Optional<Query> graphQueryOpt = graphTriplesMatcher.match(paramDTO.getGraphTriples());
        if (graphQueryOpt.isPresent()) {
            combined.should(graphQueryOpt.get());
            hasClause = true;
        }

        if (!hasClause) {
            return null;
        }

        return Query.of(q -> q.bool(combined.build()));
    }

    private boolean isEnableOcrEnabled() {
        return paramDTO.getEnableOcr() == null || paramDTO.getEnableOcr();
    }

    private boolean hasKeyword() {
        return paramDTO.getKeyword() != null && !paramDTO.getKeyword().isBlank();
    }

    private Highlight buildHighlight(boolean ocrEnabled) {
        Highlight.Builder builder = new Highlight.Builder()
                .preTags("<em>")
                .postTags("</em>")
                .requireFieldMatch(false)
                .fields("fileName", field -> field.numberOfFragments(0))
                .fields("tags", field -> field.numberOfFragments(0))
                .fields("relations.s", field -> field.numberOfFragments(0))
                .fields("relations.p", field -> field.numberOfFragments(0))
                .fields("relations.o", field -> field.numberOfFragments(0));

        if (ocrEnabled) {
            builder.fields("ocrContent", field -> field.fragmentSize(160).numberOfFragments(1));
        }
        return builder.build();
    }

    private static List<SortOptions> defaultSort() {
        return List.of(
                new SortOptions.Builder().score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)).build(),
                new SortOptions.Builder().field(f -> f.field("id").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)).build()
        );
    }
}
