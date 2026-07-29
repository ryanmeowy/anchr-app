package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.api.model.RetrievalGenerationIndexRequest;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.infrastructure.persistence.es.SearchSegmentBulkWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalGenerationIndexServiceImplTest {

    @Mock private SegmentRepository repository;
    @Mock private SearchSegmentBulkWriter writer;

    @Test
    void replaceGeneration_shouldDeleteTargetBeforeWritingAndReturnReceipt() {
        RetrievalGenerationIndexServiceImpl service = service();
        when(writer.write(any())).thenReturn(
                new SearchSegmentBulkWriter.WriteResult(1, "index", "profile"));

        var receipt = service.replaceGeneration(request());

        InOrder order = inOrder(repository, writer);
        order.verify(repository).deleteByAssetGeneration("asset-1", 2L);
        ArgumentCaptor<List<Segment>> segments = ArgumentCaptor.forClass(List.class);
        order.verify(writer).write(segments.capture());
        assertThat(segments.getValue()).singleElement().satisfies(segment -> {
            assertThat(segment.getKbId()).isEqualTo("kb-1");
            assertThat(segment.getAssetId()).isEqualTo("asset-1");
            assertThat(segment.getIndexGeneration()).isEqualTo(2L);
            assertThat(segment.getEmbedding()).containsExactly(0.1f);
        });
        assertThat(receipt.writtenCount()).isEqualTo(1);
        assertThat(receipt.indexName()).isEqualTo("index");
        assertThat(receipt.profileFingerprint()).isEqualTo("profile");
    }

    @Test
    void replaceGeneration_replayShouldReplaceTheSameGenerationAgain() {
        RetrievalGenerationIndexServiceImpl service = service();
        when(writer.write(any())).thenReturn(
                new SearchSegmentBulkWriter.WriteResult(1, "index", "profile"));

        service.replaceGeneration(request());
        service.replaceGeneration(request());

        verify(repository, times(2)).deleteByAssetGeneration("asset-1", 2L);
        verify(writer, times(2)).write(any());
    }

    @Test
    void replaceGeneration_shouldRejectMismatchedSegmentBeforeDeletingAnything() {
        RetrievalGenerationIndexRequest invalid = new RetrievalGenerationIndexRequest(
                "kb-1", "asset-1", 2L,
                List.of(segmentValue("asset-2", 2L)));

        assertThatThrownBy(() -> service().replaceGeneration(invalid))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).deleteByAssetGeneration(any(), any(Long.class));
        verify(writer, never()).write(any());
    }

    private RetrievalGenerationIndexServiceImpl service() {
        return new RetrievalGenerationIndexServiceImpl(repository, writer);
    }

    private RetrievalGenerationIndexRequest request() {
        return new RetrievalGenerationIndexRequest(
                "kb-1", "asset-1", 2L, List.of(segmentValue("asset-1", 2L)));
    }

    private RetrievalGenerationIndexRequest.SegmentValue segmentValue(
            String assetId, long generation) {
        return new RetrievalGenerationIndexRequest.SegmentValue(
                "segment-1", "kb-1", assetId, generation, "PDF", "TEXT_CHUNK",
                "title", "content", null, 1, 0, null, null, null,
                List.of(0.1f), "source", null, null, List.of("tag"), 1L);
    }
}
