package com.anchr.core.search.application.impl;

import com.anchr.core.search.application.CitationReasonGenerationService;
import com.anchr.core.search.domain.port.SearchGenerationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CitationReasonGenerationServiceImplTest {

    @Test
    void generate_shouldSendCompleteBatchAndMapOnlyKnownSegments() {
        SearchGenerationPort generationPort = mock(SearchGenerationPort.class);
        when(generationPort.generateText(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                {"items":[
                  {"segmentId":"seg-1","reason":"该段解释了外部检索如何支撑回答。"},
                  {"segmentId":"seg-1","reason":"重复结果"},
                  {"segmentId":"unknown","reason":"未知结果"}
                ]}
                """);
        CitationReasonGenerationServiceImpl service = service(generationPort);

        Map<String, String> reasons = service.generate(request());

        assertThat(reasons).containsEntry("seg-1", "该段解释了外部检索如何支撑回答。")
                .containsEntry("seg-2", "内容关键词命中");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(generationPort).generateText(prompt.capture());
        assertThat(prompt.getValue())
                .contains("用户问题", "检索改写", "最终回答[1]", "seg-1", "原始正文一", "0.65", "VECTOR", "语义匹配");
    }

    @Test
    void generate_shouldFallbackForInvalidResponse() {
        SearchGenerationPort generationPort = mock(SearchGenerationPort.class);
        when(generationPort.generateText(org.mockito.ArgumentMatchers.anyString())).thenReturn("not-json");

        assertThat(service(generationPort).generate(request()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "seg-1", "语义匹配",
                        "seg-2", "内容关键词命中"
                ));
    }

    @Test
    void generate_shouldSkipModelWhenNoChunkHasOriginalContent() {
        SearchGenerationPort generationPort = mock(SearchGenerationPort.class);
        CitationReasonGenerationService.Request request = new CitationReasonGenerationService.Request(
                "问题", null, "回答[1]", List.of(new CitationReasonGenerationService.CitationGroup(
                1, "asset-1", List.of(new CitationReasonGenerationService.CitationChunk(
                "seg-1", null, 0.2D, List.of("VECTOR"), "语义匹配"
        )))));

        assertThat(service(generationPort).generate(request)).containsEntry("seg-1", "语义匹配");
        verifyNoInteractions(generationPort);
    }

    private CitationReasonGenerationServiceImpl service(SearchGenerationPort generationPort) {
        return new CitationReasonGenerationServiceImpl(
                generationPort,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }

    private CitationReasonGenerationService.Request request() {
        return new CitationReasonGenerationService.Request(
                "用户问题",
                "检索改写",
                "最终回答[1]",
                List.of(new CitationReasonGenerationService.CitationGroup(
                        1,
                        "asset-1",
                        List.of(
                                new CitationReasonGenerationService.CitationChunk(
                                        "seg-1", "原始正文一", 0.65D, List.of("VECTOR"), "语义匹配"),
                                new CitationReasonGenerationService.CitationChunk(
                                        "seg-2", "原始正文二", 0.52D, List.of("CONTENT"), "内容关键词命中")
                        )
                ))
        );
    }
}
