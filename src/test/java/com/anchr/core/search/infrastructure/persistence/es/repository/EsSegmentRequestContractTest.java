package com.anchr.core.search.infrastructure.persistence.es.repository;

import co.elastic.clients.elasticsearch._types.SortOrder;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.application.SegmentRebuildMutationTracker;
import com.anchr.core.search.infrastructure.persistence.es.document.SegmentDocument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EsSegmentRequestContractTest {

    @Test
    void textSearchShouldExcludeVisualProjectionWhileVectorSearchKeepsIt() {
        EsSegmentRepository repository = repository();

        var text = repository.buildTextSearchRequest(
                "diagram", List.of(), 10, null);
        var vector = repository.buildVectorSearchRequest(
                List.of(0.1f, 0.2f), 10, null);

        assertThat(text.query().bool().mustNot()).singleElement()
                .satisfies(query -> {
                    assertThat(query.term().field()).isEqualTo("segmentType");
                    assertThat(query.term().value().stringValue())
                            .isEqualTo(SegmentType.IMAGE_VISUAL.name());
                });
        assertThat(vector.knn()).singleElement().satisfies(knn -> {
            assertThat(knn.field()).isEqualTo("embedding");
            assertThat(knn.filter()).isEmpty();
        });
        assertThat(text.source().filter().excludes())
                .containsExactly("embedding");
        assertThat(vector.source().filter().excludes())
                .containsExactly("embedding");
    }

    @Test
    void assetListingShouldKeepGenerationAndCursorContractWithoutVisuals() {
        var request = repository().buildAssetSegmentsRequest(
                " kb-1 ", " asset-1 ", 3L, 7, " seg-7 ", 20);

        assertThat(request.index()).containsExactly("kb_segment_read");
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.query().bool().filter()).hasSize(3);
        assertThat(request.query().bool().filter().get(0).term().field())
                .isEqualTo("kbId");
        assertThat(request.query().bool().filter().get(1).term().field())
                .isEqualTo("assetId");
        assertThat(request.query().bool().filter().get(2).term().field())
                .isEqualTo("indexGeneration");
        assertThat(request.query().bool().mustNot()).singleElement()
                .satisfies(query -> assertThat(query.term().value().stringValue())
                        .isEqualTo(SegmentType.IMAGE_VISUAL.name()));
        assertThat(request.sort()).hasSize(2);
        assertThat(request.sort().get(0).field().field())
                .isEqualTo("chunkOrder");
        assertThat(request.sort().get(0).field().order())
                .isEqualTo(SortOrder.Asc);
        assertThat(request.sort().get(1).field().field())
                .isEqualTo("segmentId");
        assertThat(request.searchAfter()).hasSize(2);
        assertThat(request.searchAfter().get(0).longValue()).isEqualTo(7L);
        assertThat(request.searchAfter().get(1).stringValue())
                .isEqualTo("seg-7");
        assertThat(request.source().filter().excludes())
                .containsExactly("embedding");
    }

    @Test
    void directSegmentReadShouldExcludeEmbedding() {
        var request = repository().buildFindBySegmentIdRequest(" seg-1 ");

        assertThat(request.index()).isEqualTo("kb_segment_read");
        assertThat(request.id()).isEqualTo("seg-1");
        assertThat(request.sourceExcludes()).containsExactly("embedding");
    }

    @Test
    void visualProjectionShouldMapBackToDomainSegment() {
        SegmentDocument document = new SegmentDocument();
        document.setSegmentId("visual-1");
        document.setSegmentType(SegmentType.IMAGE_VISUAL.name());

        Segment segment = ReflectionTestUtils.invokeMethod(
                repository(), "toSegment", document);

        assertThat(segment).isNotNull();
        assertThat(segment.getSegmentType())
                .isEqualTo(SegmentType.IMAGE_VISUAL);
    }

    private EsSegmentRepository repository() {
        return new EsSegmentRepository(
                null, null, null, new SegmentRebuildMutationTracker());
    }
}
