package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.SearchFilter;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.PreviewNeighborsDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentPreviewServiceImplTest {

    @Test
    void getSegmentNeighbors_shouldIncludeNextChunkFromFollowingPage() {
        Segment previous = segment("previous", 4, 10, "Previous chunk");
        Segment current = segment("current", 4, 11, "Current chunk");
        Segment next = segment("next", 5, 12, "Next chunk");
        StubSegmentRepository repository = new StubSegmentRepository(
                current,
                List.of(previous, current, next));
        SegmentPreviewServiceImpl service = new SegmentPreviewServiceImpl(
                repository, null, null, null, null, null, null);

        PreviewNeighborsDTO result = service.getSegmentNeighbors("current", 1, 1);

        assertThat(result.getItems())
                .extracting("relation")
                .containsExactly("previous", "current", "next");
        assertThat(result.getItems())
                .extracting("pageNo")
                .containsExactly(4, 4, 5);
        assertThat(repository.requestedAssetId).isEqualTo("asset-1");
        assertThat(repository.requestedChunkOrder).isEqualTo(11);
        assertThat(repository.requestedWindow).isEqualTo(1);
    }

    private Segment segment(String segmentId, int pageNo, int chunkOrder, String content) {
        return Segment.builder()
                .segmentId(segmentId)
                .assetId("asset-1")
                .pageNo(pageNo)
                .chunkOrder(chunkOrder)
                .contentText(content)
                .build();
    }

    private static class StubSegmentRepository implements SegmentRepository {

        private final Segment current;
        private final List<Segment> neighbors;
        private String requestedAssetId;
        private Integer requestedChunkOrder;
        private int requestedWindow;

        private StubSegmentRepository(Segment current, List<Segment> neighbors) {
            this.current = current;
            this.neighbors = neighbors;
        }

        @Override
        public Optional<Segment> findBySegmentId(String segmentId) {
            return Optional.of(current);
        }

        @Override
        public List<Segment> findNeighborChunks(String assetId, Integer chunkOrder, int window) {
            requestedAssetId = assetId;
            requestedChunkOrder = chunkOrder;
            requestedWindow = window;
            return neighbors;
        }

        @Override
        public List<SegmentHit> textSearch(String query, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SegmentHit> textSearch(String query, int limit, SearchFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SegmentHit> textSearch(
                String query, List<String> keywords, int limit, SearchFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SegmentHit> vectorSearch(List<Float> queryVector, int topK) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SegmentHit> vectorSearch(
                List<Float> queryVector, int topK, SearchFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteByAssetId(String assetId) {
            throw new UnsupportedOperationException();
        }
    }
}
