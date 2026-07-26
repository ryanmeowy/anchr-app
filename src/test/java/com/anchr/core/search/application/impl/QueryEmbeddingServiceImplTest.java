package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.application.IndexRuntimeContext;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.IndexRuntimeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QueryEmbeddingServiceImplTest {

    @Test
    void queryShouldAlwaysUseTextInput() {
        AtomicReference<String> source = new AtomicReference<>();
        AtomicReference<String> sourceType = new AtomicReference<>();
        SearchEmbeddingPort port = (value, type) -> {
            source.set(value);
            sourceType.set(type);
            return List.of(0.1f, 0.2f);
        };

        List<Float> result =
                new QueryEmbeddingServiceImpl(port).embedQuery("  search query  ");

        assertThat(result).containsExactly(0.1f, 0.2f);
        assertThat(source.get()).isEqualTo("search query");
        assertThat(sourceType.get()).isEqualTo("text");
    }

    @Test
    void queryShouldUseTheSessionBoundToTheRequestPhysicalIndex() {
        SearchEmbeddingPort fallback = (value, type) -> {
            throw new AssertionError("legacy active client must not be used");
        };
        QueryEmbeddingServiceImpl service = new QueryEmbeddingServiceImpl(fallback);
        IndexRuntimeContext context = new IndexRuntimeContext();
        service.setRuntimeContext(context);
        EmbeddingProfile profile =
                new EmbeddingProfile(7L, "EMBEDDING", "serving", 2, "serving-fp");
        IndexRuntimeSnapshot snapshot = new IndexRuntimeSnapshot(
                "kb_segment_v7", profile,
                (source, type) -> List.of(0.7F, 0.8F),
                IndexRuntimeSnapshot.RetrievalPlan.singleEmbeddingV1());

        List<Float> result = context.withSnapshot(
                snapshot, () -> service.embedQuery("query"));

        assertThat(result).containsExactly(0.7F, 0.8F);
    }
}
