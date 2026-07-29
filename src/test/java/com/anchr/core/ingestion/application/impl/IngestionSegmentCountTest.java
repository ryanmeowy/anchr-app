package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.application.model.IngestionIndexSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionSegmentCountTest {

    @Test
    void assetCountShouldExcludeTheAssetLevelVisualProjection() {
        List<IngestionIndexSegment> segments = List.of(
                segment("ocr-1", "IMAGE_OCR_BLOCK"),
                segment("ocr-2", "IMAGE_OCR_BLOCK"),
                segment("visual", "IMAGE_VISUAL"));

        assertThat(IngestionIndexFinalizer.countReadableSegments(segments))
                .isEqualTo(2);
    }

    private IngestionIndexSegment segment(String id, String type) {
        return new IngestionIndexSegment(
                id, "kb-1", "asset-1", 2L, "IMAGE", type,
                null, null, null, null, 0, null, null, null,
                null, null, null, null, null, 1L);
    }
}
