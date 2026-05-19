package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.search.domain.port.SearchEmbeddingPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbQueryEmbeddingServiceImplTest {

    @Mock
    private SearchEmbeddingPort searchEmbeddingPort;

    @Test
    void embedQuery_shouldReturnEmbedding() {
        KbQueryEmbeddingServiceImpl service = new KbQueryEmbeddingServiceImpl(searchEmbeddingPort);
        when(searchEmbeddingPort.embedText("invoice")).thenReturn(List.of(0.1f, 0.2f));

        List<Float> embedding = service.embedQuery("invoice");

        assertThat(embedding).containsExactly(0.1f, 0.2f);
    }

    @Test
    void embedQuery_shouldThrowWhenEmbeddingEmpty() {
        KbQueryEmbeddingServiceImpl service = new KbQueryEmbeddingServiceImpl(searchEmbeddingPort);
        when(searchEmbeddingPort.embedText("invoice")).thenReturn(List.of());

        assertThatThrownBy(() -> service.embedQuery("invoice"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Embedding result is empty, please retry later.");
    }
}
