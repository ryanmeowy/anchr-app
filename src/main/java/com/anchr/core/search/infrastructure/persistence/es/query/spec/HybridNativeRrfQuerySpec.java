package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import cn.hutool.core.collection.CollectionUtil;
import co.elastic.clients.elasticsearch._types.Retriever;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.search.domain.model.HybridSearchParamDTO;
import com.anchr.core.search.infrastructure.persistence.es.query.GraphTriplesMatcher;
import com.anchr.core.search.infrastructure.persistence.es.query.HybridSearchKeywordMatcher;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hybrid search request powered by Elasticsearch native retriever RRF.
 */
public class HybridNativeRrfQuerySpec implements QuerySpec {

    private final String indexName;
    private final HybridSearchParamDTO paramDTO;
    private final HybridSearchKeywordMatcher keywordMatcher;
    private final GraphTriplesMatcher graphTriplesMatcher;
    private final Integer rankConstant;
    private final Integer rankWindowSize;

    public HybridNativeRrfQuerySpec(String indexName,
                                    HybridSearchParamDTO paramDTO,
                                    HybridSearchKeywordMatcher keywordMatcher,
                                    GraphTriplesMatcher graphTriplesMatcher,
                                    Integer rankConstant,
                                    Integer rankWindowSize) {
        this.indexName = indexName;
        this.paramDTO = paramDTO;
        this.keywordMatcher = keywordMatcher;
        this.graphTriplesMatcher = graphTriplesMatcher;
        this.rankConstant = rankConstant;
        this.rankWindowSize = rankWindowSize;
    }

    @Override
    public SearchRequest toSearchRequest() {
        SearchRequest.Builder builder = new SearchRequest.Builder();
        builder.index(indexName);
        builder.size(resolveSize());
        builder.source(s -> s.filter(f -> f.excludes("imageEmbedding")));

        Query mergedQuery = buildMainQuery().orElse(null);
        if (mergedQuery != null) {
            builder.highlight(buildHighlight(isEnableOcrEnabled()));
        }

        List<Retriever> retrievers = buildRetrievers(mergedQuery);
        if (CollectionUtil.isEmpty(retrievers)) {
            builder.query(Query.of(q -> q.matchNone(m -> m)));
            return builder.build();
        }

        builder.retriever(r -> r.rrf(rrf -> rrf
                .rankConstant(resolveRankConstant())
                .rankWindowSize(resolveRankWindowSize())
                .retrievers(retrievers)
        ));
        return builder.build();
    }

    private List<Retriever> buildRetrievers(Query mergedQuery) {
        List<Retriever> retrievers = new ArrayList<>();
        if (mergedQuery != null) {
            retrievers.add(Retriever.of(r -> r.standard(s -> s
                    .query(mergedQuery)
                    .sort(defaultSort())
                    .name("standard")
            )));
        }

        if (CollectionUtil.isNotEmpty(paramDTO.getQueryVector())) {
            int topK = resolveTopK();
            int numCandidates = Math.max(
                    EmbeddingConstant.DEFAULT_NUM_CANDIDATES,
                    topK * EmbeddingConstant.NUM_CANDIDATES_FACTOR
            );
            retrievers.add(Retriever.of(r -> r.knn(k -> k
                    .field("imageEmbedding")
                    .queryVector(paramDTO.getQueryVector())
                    .k(topK)
                    .numCandidates(numCandidates)
                    .name("knn")
            )));
        }
        return retrievers;
    }

    private Optional<Query> buildMainQuery() {
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
            return Optional.empty();
        }
        return Optional.of(Query.of(q -> q.bool(combined.build())));
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

    private int resolveTopK() {
        return Optional.ofNullable(paramDTO.getTopK())
                .map(value -> Math.max(1, value))
                .orElse(EmbeddingConstant.DEFAULT_TOP_K);
    }

    private int resolveSize() {
        return Optional.ofNullable(paramDTO.getLimit())
                .map(value -> Math.max(1, value))
                .orElse(EmbeddingConstant.DEFAULT_TOP_K);
    }

    private int resolveRankConstant() {
        return Math.max(1, Optional.ofNullable(rankConstant).orElse(60));
    }

    private int resolveRankWindowSize() {
        return Math.max(1, Optional.ofNullable(rankWindowSize).orElse(resolveSize()));
    }

    private boolean hasKeyword() {
        return StringUtils.hasText(paramDTO.getKeyword());
    }

    private boolean isEnableOcrEnabled() {
        return paramDTO.getEnableOcr() == null || paramDTO.getEnableOcr();
    }

    private static List<SortOptions> defaultSort() {
        return List.of(
                new SortOptions.Builder().score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)).build(),
                new SortOptions.Builder().field(f -> f.field("id").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)).build()
        );
    }
}
