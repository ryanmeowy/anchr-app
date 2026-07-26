package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.port.SearchEmbeddingPort;
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
}
