package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import com.anchr.core.common.constant.SearchConstant;
import com.anchr.core.search.infrastructure.persistence.es.query.HybridSearchKeywordMatcher;
import java.util.List;
import java.util.Optional;
import org.springframework.util.StringUtils;

/**
 * Text-only search spec based on keyword matching over OCR/tags/file name.
 */
public class TextOnlyQuerySpec implements QuerySpec {

    private final String indexName;
    private final String keyword;
    private final Integer limit;
    private final Boolean enableOcr;
    private final HybridSearchKeywordMatcher keywordMatcher;

    public TextOnlyQuerySpec(String indexName,
                             String keyword,
                             Integer limit,
                             Boolean enableOcr,
                             HybridSearchKeywordMatcher keywordMatcher) {
        this.indexName = indexName;
        this.keyword = keyword;
        this.limit = limit;
        this.enableOcr = enableOcr;
        this.keywordMatcher = keywordMatcher;
    }

    @Override
    public SearchRequest toSearchRequest() {
        int safeLimit = limit == null || limit <= 0 ? SearchConstant.DEFAULT_RESULT_LIMIT : limit;
        SearchRequest.Builder builder = new SearchRequest.Builder();
        builder.index(indexName);
        builder.size(safeLimit);
        builder.sort(defaultSort());
        builder.source(s -> s.filter(f -> f.excludes("imageEmbedding")));

        if (!StringUtils.hasText(keyword)) {
            builder.query(Query.of(q -> q.matchNone(m -> m)));
            return builder.build();
        }

        boolean ocrEnabled = enableOcr == null || enableOcr;
        builder.highlight(buildHighlight(ocrEnabled));
        Optional<Query> queryOpt = keywordMatcher.match(keyword, ocrEnabled);
        builder.query(queryOpt.orElseGet(() -> Query.of(q -> q.matchNone(m -> m))));
        return builder.build();
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
