package com.smart.vision.core.ingestion.infrastructure.persistence.es;

import com.smart.vision.core.ingestion.domain.model.TextChunk;
import com.smart.vision.core.search.domain.model.KbAssetTypeEnum;
import com.smart.vision.core.search.domain.model.Segment;
import com.smart.vision.core.search.domain.model.SegmentType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TextSegmentIndexWriterTest {

    @Test
    void save_shouldMapChunksToTextSegments() {
        KbSegmentBulkWriter bulkWriter = mock(KbSegmentBulkWriter.class);
        TextSegmentIndexWriter writer = new TextSegmentIndexWriter(bulkWriter);

        TextChunk chunk = new TextChunk("s-1", "asset-1", "doc", 2, "hello world", 0, "oss://k", List.of(0.3f, 0.7f));
        writer.save("asset-1", List.of(chunk));

        ArgumentCaptor<List<Segment>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkWriter).write(captor.capture());
        List<Segment> segments = captor.getValue();
        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().getSegmentType()).isEqualTo(SegmentType.TEXT_CHUNK);
        assertThat(segments.getFirst().getAssetType()).isEqualTo(KbAssetTypeEnum.TEXT);
        assertThat(segments.getFirst().getContentText()).isEqualTo("hello world");
        assertThat(segments.getFirst().getEmbedding()).containsExactly(0.3f, 0.7f);
    }

    @Test
    void save_shouldSkipWhenAssetIdIsBlank() {
        KbSegmentBulkWriter bulkWriter = mock(KbSegmentBulkWriter.class);
        TextSegmentIndexWriter writer = new TextSegmentIndexWriter(bulkWriter);

        writer.save(" ", List.of(new TextChunk()));

        verifyNoInteractions(bulkWriter);
    }
}
