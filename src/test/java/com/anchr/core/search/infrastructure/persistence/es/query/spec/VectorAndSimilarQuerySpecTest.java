package com.anchr.core.search.infrastructure.persistence.es.query.spec;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.anchr.core.common.constant.EmbeddingConstant;
import com.anchr.core.search.infrastructure.persistence.es.query.SimilarSearchIdMatcher;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class VectorAndSimilarQuerySpecTest {

    @Test
    void vectorOnlySpec_shouldUseDefaultTopK_whenTopKIsInvalid() {
        VectorOnlyQuerySpec spec = new VectorOnlyQuerySpec("idx", List.of(0.1f, 0.2f), 0, 0.72d);

        SearchRequest request = spec.toSearchRequest();

        assertThat(request.size()).isEqualTo(EmbeddingConstant.DEFAULT_TOP_K);
        assertThat(request.knn()).isNotNull();
        assertThat(request.knn()).hasSize(1);
        assertThat(request.minScore()).isEqualTo(0.72d);
    }

    @Test
    void similarQuerySpec_shouldThrow_whenExcludeIdMatcherReturnsEmpty() {
        SimilarSearchIdMatcher matcher = Mockito.mock(SimilarSearchIdMatcher.class);
        when(matcher.match(eq("bad-id"))).thenReturn(Optional.empty());
        SimilarQuerySpec spec = new SimilarQuerySpec("idx", List.of(0.1f, 0.2f), 10, "bad-id", matcher);

        assertThatThrownBy(spec::toSearchRequest)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("excludeDocId cannot be empty");
    }

    @Test
    void similarQuerySpec_shouldBuildKnnAndFilter_whenExcludeIdIsValid() {
        SimilarSearchIdMatcher matcher = Mockito.mock(SimilarSearchIdMatcher.class);
        Query filterQuery = Query.of(q -> q.bool(b -> b.mustNot(mn -> mn.term(t -> t.field("id").value(1L)))));
        when(matcher.match(eq("1"))).thenReturn(Optional.of(filterQuery));
        SimilarQuerySpec spec = new SimilarQuerySpec("idx", List.of(0.1f, 0.2f), 10, "1", matcher);

        SearchRequest request = spec.toSearchRequest();

        assertThat(request.query()).isNotNull();
        assertThat(request.knn()).isNotNull();
        assertThat(request.knn()).hasSize(1);
    }
}
