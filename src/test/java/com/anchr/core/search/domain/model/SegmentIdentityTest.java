package com.anchr.core.search.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentIdentityTest {

    @Test
    void extractedChunkIdentityShouldPreserveThe107Hash() {
        assertThat(SegmentIdentity.chunk(
                "asset-1", 7L, "chunk/0", 1, 0, "ignored"))
                .isEqualTo(
                        "da1466f17471b069bbd3ca625acae5ad8485a895399a0afcaee59c3ca89050ee");
    }

    @Test
    void imageVisualIdentityShouldBeStableAndGenerationScoped() {
        String first = SegmentIdentity.imageVisual("asset-1", 4L);
        String retried = SegmentIdentity.imageVisual("asset-1", 4L);
        String nextGeneration = SegmentIdentity.imageVisual("asset-1", 5L);
        String chunk = SegmentIdentity.chunk(
                "asset-1", 4L, "chunk/0", 1, 0, "text");

        assertThat(first)
                .matches("[0-9a-f]{64}")
                .isEqualTo(retried)
                .isNotEqualTo(nextGeneration)
                .isNotEqualTo(chunk);
    }

    @Test
    void imageVisualIdentityShouldSupportLegacyGenerationZeroDuringRebuild() {
        assertThat(SegmentIdentity.imageVisual("asset-1", 0L))
                .matches("[0-9a-f]{64}");
    }
}
