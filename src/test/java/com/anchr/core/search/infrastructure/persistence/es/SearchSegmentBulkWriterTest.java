package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.application.SegmentRebuildMutationTracker;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchSegmentBulkWriterTest {

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private SegmentIndexManager segmentIndexManager;

    private SearchSegmentBulkWriter writer;

    @BeforeEach
    void setUp() {
        writer = new SearchSegmentBulkWriter(
                esClient,
                segmentIndexManager,
                new SegmentIndexWriteBarrier(),
                new SegmentRebuildMutationTracker());
    }

    @Test
    void write_shouldRejectNullSegmentInsteadOfSilentlyDroppingIt() {
        List<Segment> segments = Arrays.asList(segment("segment-1", 3L), null);

        assertThatThrownBy(() -> writer.write(segments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("segments[1] cannot be null.");

        verifyNoInteractions(esClient);
    }

    @Test
    void write_shouldRejectBlankSegmentIdInsteadOfSilentlyDroppingIt() {
        assertThatThrownBy(() -> writer.write(List.of(segment(" ", 3L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("segments[0].segmentId cannot be blank.");

        verifyNoInteractions(esClient);
    }

    @Test
    void write_shouldRejectBulkResponseSizeMismatch() throws Exception {
        indexWritable();
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(
                BulkResponse.of(response -> response
                        .errors(false)
                        .took(1)
                        .items(List.of())));

        assertThatThrownBy(() -> writer.write(List.of(segment("segment-1", 3L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expected 1, actual 0");
    }

    @Test
    void write_shouldRejectPartialBulkFailure() throws Exception {
        indexWritable();
        BulkResponseItem success = successfulItem("segment-1");
        BulkResponseItem failed = BulkResponseItem.of(item -> item
                .operationType(OperationType.Index)
                .index("kb_segment_write")
                .id("segment-2")
                .status(400)
                .error(ErrorCause.of(error -> error
                        .type("mapper_parsing_exception")
                        .reason("mapping rejected"))));
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(
                BulkResponse.of(response -> response
                        .errors(false)
                        .took(1)
                        .items(success, failed)));

        assertThatThrownBy(() -> writer.write(List.of(
                segment("segment-1", 3L),
                segment("segment-2", 3L))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("mapping rejected");
    }

    @Test
    void write_shouldPreserveIdAndIndexGenerationInBulkDocument() throws Exception {
        indexWritable();
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(
                BulkResponse.of(response -> response
                        .errors(false)
                        .took(1)
                        .items(successfulItem("segment-1"))));

        writer.write(List.of(segment("segment-1", 9L)));

        ArgumentCaptor<BulkRequest> request = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(request.capture());
        assertThat(request.getValue().refresh()).isEqualTo(Refresh.WaitFor);
        var operation = request.getValue().operations().getFirst().index();
        assertThat(operation.index()).isEqualTo("kb_segment_write");
        assertThat(operation.id()).isEqualTo("segment-1");
        assertThat(operation.document()).isInstanceOfSatisfying(
                SegmentDocument.class,
                document -> {
                    assertThat(document.getSegmentId()).isEqualTo("segment-1");
                    assertThat(document.getIndexGeneration()).isEqualTo(9L);
                    assertThat(document.getSegmentType())
                            .isEqualTo(SegmentType.IMAGE_VISUAL.name());
                    assertThat(document.getEmbedding())
                            .containsExactly(0.1f, 0.2f);
                });
    }

    private Segment segment(String segmentId, long indexGeneration) {
        return Segment.builder()
                .segmentId(segmentId)
                .kbId("kb-1")
                .assetId("asset-1")
                .indexGeneration(indexGeneration)
                .segmentType(SegmentType.IMAGE_VISUAL)
                .embedding(List.of(0.1f, 0.2f))
                .build();
    }

    private void indexWritable() {
        when(segmentIndexManager.status()).thenReturn(
                SegmentIndexStatusDTO.builder().writable(true).build());
    }

    private BulkResponseItem successfulItem(String segmentId) {
        return BulkResponseItem.of(item -> item
                .operationType(OperationType.Index)
                .index("kb_segment_write")
                .id(segmentId)
                .status(201));
    }
}
