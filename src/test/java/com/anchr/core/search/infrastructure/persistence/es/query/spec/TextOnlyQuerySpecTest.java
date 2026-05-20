package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.anchr.core.common.constant.SearchConstant;
import com.anchr.core.search.infrastructure.persistence.es.query.HybridSearchKeywordMatcher;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextOnlyQuerySpecTest {

    @Test
    void toSearchRequest_shouldUseDefaultLimitAndMatchNone_whenKeywordIsBlank() {
        HybridSearchKeywordMatcher matcher = Mockito.mock(HybridSearchKeywordMatcher.class);
        TextOnlyQuerySpec spec = new TextOnlyQuerySpec("idx", " ", 0, true, matcher);

        SearchRequest request = spec.toSearchRequest();

        assertThat(request.index()).containsExactly("idx");
        assertThat(request.size()).isEqualTo(SearchConstant.DEFAULT_RESULT_LIMIT);
        assertThat(request.query()).isNotNull();
        assertThat(request.query().isMatchNone()).isTrue();
        verify(matcher, never()).match(eq(" "), anyBoolean());
    }

    @Test
    void toSearchRequest_shouldFallbackToMatchNone_whenMatcherReturnsEmpty() {
        HybridSearchKeywordMatcher matcher = Mockito.mock(HybridSearchKeywordMatcher.class);
        when(matcher.match(eq("invoice"), eq(false))).thenReturn(Optional.empty());
        TextOnlyQuerySpec spec = new TextOnlyQuerySpec("idx", "invoice", 5, false, matcher);

        SearchRequest request = spec.toSearchRequest();

        assertThat(request.size()).isEqualTo(5);
        assertThat(request.query()).isNotNull();
        assertThat(request.query().isMatchNone()).isTrue();
        assertThat(request.highlight()).isNotNull();
        assertThat(request.highlight().fields().keySet()).contains("fileName", "tags");
        assertThat(request.highlight().fields().keySet()).doesNotContain("ocrContent");
        verify(matcher).match("invoice", false);
    }

    @Test
    void toSearchRequest_shouldUseMatcherQuery_whenKeywordPresent() {
        HybridSearchKeywordMatcher matcher = Mockito.mock(HybridSearchKeywordMatcher.class);
        Query query = Query.of(q -> q.match(m -> m.field("fileName").query("cat")));
        when(matcher.match(eq("cat"), eq(true))).thenReturn(Optional.of(query));
        TextOnlyQuerySpec spec = new TextOnlyQuerySpec("idx", "cat", 8, null, matcher);

        SearchRequest request = spec.toSearchRequest();

        assertThat(request.size()).isEqualTo(8);
        assertThat(request.query()).isNotNull();
        assertThat(request.query().isMatch()).isTrue();
        assertThat(request.highlight()).isNotNull();
        assertThat(request.highlight().fields().keySet()).contains("fileName", "tags", "ocrContent", "relations.s", "relations.p", "relations.o");
        verify(matcher).match("cat", true);
    }
}
