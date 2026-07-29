package com.anchr.core.search.infrastructure.persistence.es;

import com.anchr.core.common.config.SegmentIndexConfig;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.SegmentIndexManager;
import com.anchr.core.search.application.SegmentIndexWriteBarrier;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentIndexStatus;
import com.anchr.core.search.infrastructure.persistence.es.repository.EsSegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.SegmentIndexStatusDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SegmentIndexAvailabilityTest {

    @Test
    void readsShouldRemainAvailableWhileRebuildBlocksWrites() {
        SegmentIndexStatusDTO rebuilding = SegmentIndexStatusDTO.builder()
                .status(SegmentIndexStatus.REBUILDING)
                .indexExists(true)
                .readable(true)
                .writable(false)
                .build();
        SegmentIndexManager manager = new StubSegmentIndexManager(rebuilding);
        SegmentIndexConfig config = config();
        SegmentIndexWriteBarrier barrier = new SegmentIndexWriteBarrier();
        EsSegmentRepository repository =
                new EsSegmentRepository(null, config, manager, barrier);
        SearchSegmentBulkWriter bulkWriter =
                new SearchSegmentBulkWriter(null, config, manager, barrier);

        assertDoesNotThrow(() -> repository.textSearch("", 0));
        assertThrows(BusinessException.class, () ->
                bulkWriter.write(List.of(Segment.builder().segmentId("segment-1").build())));
        assertThrows(BusinessException.class, () ->
                repository.deleteByAssetId("asset-1"));
    }

    private SegmentIndexConfig config() {
        SegmentIndexConfig config = new SegmentIndexConfig();
        config.setReadAlias("kb_segment_read");
        config.setWriteAlias("kb_segment_write");
        return config;
    }

    private record StubSegmentIndexManager(
            SegmentIndexStatusDTO status
    ) implements SegmentIndexManager {

        @Override
        public void asyncCreate() {}

        @Override
        public boolean retryCreate() {
            return false;
        }

        @Override
        public boolean confirmRebuild(String taskId) {
            return false;
        }

        @Override
        public String prepareRebuild() {
            return null;
        }

        @Override
        public String requestRebuild(EmbeddingProfile targetProfile) {
            return null;
        }
    }
}
