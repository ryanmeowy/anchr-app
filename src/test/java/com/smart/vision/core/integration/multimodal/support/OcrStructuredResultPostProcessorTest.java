package com.smart.vision.core.integration.multimodal.support;

import com.smart.vision.core.ingestion.domain.model.OcrBoundingBox;
import com.smart.vision.core.ingestion.domain.model.OcrParagraph;
import com.smart.vision.core.ingestion.domain.model.OcrStructuredResult;
import com.smart.vision.core.integration.multimodal.port.OcrParagraphEnhancementPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcrStructuredResultPostProcessorTest {

    @Test
    void process_shouldKeepOriginalWhenEnhancedTextDrifts() {
        OcrParagraphEnhancementPort enhancementPort = mock(OcrParagraphEnhancementPort.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OcrStructuredResultPostProcessor processor = new OcrStructuredResultPostProcessor(enhancementPort, meterRegistry);

        OcrParagraph paragraph = OcrParagraph.builder()
                .index(0)
                .text("E102")
                .bbox(OcrBoundingBox.pixel(1, 2, 3, 4))
                .build();
        when(enhancementPort.enhanceParagraph(eq("oss://img.png"), any(OcrParagraph.class), eq(100), eq(100)))
                .thenReturn("unrelated long sentence");

        OcrStructuredResult result = processor.process("oss://img.png", OcrStructuredResult.builder()
                .imageWidth(100)
                .imageHeight(100)
                .paragraphs(List.of(paragraph))
                .build());

        assertThat(result.getFullText()).isEqualTo("E102");
        assertThat(result.getParagraphs().getFirst().getText()).isEqualTo("E102");
        assertThat(meterRegistry.counter("smartvision.ingestion.ocr.text_drift").count()).isEqualTo(1d);
    }

    @Test
    void process_shouldCapDenseParagraphs() {
        OcrParagraphEnhancementPort enhancementPort = mock(OcrParagraphEnhancementPort.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OcrStructuredResultPostProcessor processor = new OcrStructuredResultPostProcessor(enhancementPort, meterRegistry);

        OcrStructuredResult result = processor.process("oss://img.png", OcrStructuredResult.builder()
                .paragraphs(buildParagraphs(31))
                .build());

        assertThat(result.getParagraphs()).hasSize(16);
        assertThat(meterRegistry.counter("smartvision.ingestion.ocr.paragraph_capped").count()).isEqualTo(1d);
    }

    private List<OcrParagraph> buildParagraphs(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> OcrParagraph.builder()
                        .index(i)
                        .text("p" + i)
                        .bbox(OcrBoundingBox.pixel(i, i, 1, 1))
                        .build())
                .toList();
    }
}
