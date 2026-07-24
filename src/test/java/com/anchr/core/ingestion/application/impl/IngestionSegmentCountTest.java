package com.anchr.core.ingestion.application.impl;

import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionSegmentCountTest {

    @Test
    void assetCountShouldExcludeTheAssetLevelVisualProjection() {
        List<Segment> segments = List.of(
                segment("ocr-1", SegmentType.IMAGE_OCR_BLOCK),
                segment("ocr-2", SegmentType.IMAGE_OCR_BLOCK),
                segment("visual", SegmentType.IMAGE_VISUAL));

        assertThat(IngestionIndexFinalizer.countReadableSegments(segments))
                .isEqualTo(2);
    }

    private Segment segment(String id, SegmentType type) {
        return Segment.builder()
                .segmentId(id)
                .segmentType(type)
                .build();
    }
}
