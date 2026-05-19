package com.smart.vision.core.ingestion.infrastructure.persistence.es;

import com.smart.vision.core.ingestion.domain.model.OcrBoundingBox;
import com.smart.vision.core.ingestion.domain.model.OcrParagraph;
import com.smart.vision.core.ingestion.domain.model.OcrStructuredResult;
import com.smart.vision.core.ingestion.infrastructure.persistence.es.document.IngestionImageDocument;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.domain.model.SegmentType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ImageSegmentIndexWriterTest {

    @Test
    void write_shouldBuildCaptionAndOcrSegments() {
        KbSegmentBulkWriter bulkWriter = mock(KbSegmentBulkWriter.class);
        ImageSegmentIndexWriter writer = new ImageSegmentIndexWriter(bulkWriter, new SimpleMeterRegistry());

        IngestionImageDocument doc = new IngestionImageDocument();
        doc.setId(11L);
        doc.setImagePath("images/a.png");
        doc.setRawFilename("a.png");
        doc.setFileName("cat on sofa");
        doc.setOcrContent("invoice no 001");
        doc.setTags(List.of("cat", "sofa"));
        doc.setImageEmbedding(List.of(0.1f, 0.2f));
        doc.setCreateTime(123456L);

        writer.write(doc);

        ArgumentCaptor<List<Segment>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkWriter).write(captor.capture());
        List<Segment> segments = captor.getValue();
        assertThat(segments).hasSize(2);
        assertThat(segments).extracting(Segment::getSegmentType)
                .containsExactly(SegmentType.IMAGE_CAPTION, SegmentType.IMAGE_OCR_BLOCK);
        assertThat(segments.getFirst().getContentText()).isEqualTo("cat on sofa");
        assertThat(segments.get(1).getOcrText()).isEqualTo("invoice no 001");
        assertThat(segments.getFirst().getTags()).containsExactly("cat", "sofa");
        assertThat(segments.get(1).getTags()).containsExactly("cat", "sofa");
    }

    @Test
    void write_shouldBuildParagraphOcrSegmentsWithBbox() {
        KbSegmentBulkWriter bulkWriter = mock(KbSegmentBulkWriter.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ImageSegmentIndexWriter writer = new ImageSegmentIndexWriter(bulkWriter, meterRegistry);

        IngestionImageDocument doc = new IngestionImageDocument();
        doc.setId(12L);
        doc.setImagePath("images/b.png");
        doc.setRawFilename("b.png");
        doc.setFileName("receipt");
        doc.setOcrContent("line one\nline two");
        doc.setImageEmbedding(List.of(0.1f, 0.2f));
        doc.setCreateTime(123456L);
        doc.setStructuredOcr(OcrStructuredResult.builder()
                .fullText("line one\nline two")
                .imageWidth(200)
                .imageHeight(100)
                .paragraphs(List.of(
                        OcrParagraph.builder()
                                .index(0)
                                .text("line one")
                                .bbox(OcrBoundingBox.pixel(10, 20, 50, 10))
                                .build(),
                        OcrParagraph.builder()
                                .index(1)
                                .text("line two")
                                .bbox(OcrBoundingBox.pixel(10, 40, 60, 10))
                                .build()
                ))
                .build());

        writer.write(doc);

        ArgumentCaptor<List<Segment>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkWriter).write(captor.capture());
        List<Segment> segments = captor.getValue();
        assertThat(segments).hasSize(3);
        assertThat(segments.get(1).getSegmentId()).isEqualTo("12:ocr:0");
        assertThat(segments.get(1).getOcrText()).isEqualTo("line one");
        assertThat(segments.get(1).getBbox().getX()).isEqualTo(10);
        assertThat(segments.get(1).getImageWidth()).isEqualTo(200);
        assertThat(segments.get(2).getSegmentId()).isEqualTo("12:ocr:1");
        assertThat(meterRegistry.counter("smartvision.ingestion.bbox.write_success").count()).isEqualTo(2d);
    }

    @Test
    void write_shouldSkipWhenDocumentIdIsMissing() {
        KbSegmentBulkWriter bulkWriter = mock(KbSegmentBulkWriter.class);
        ImageSegmentIndexWriter writer = new ImageSegmentIndexWriter(bulkWriter, new SimpleMeterRegistry());

        IngestionImageDocument doc = new IngestionImageDocument();
        doc.setId(null);
        writer.write(doc);

        verifyNoInteractions(bulkWriter);
    }
}
