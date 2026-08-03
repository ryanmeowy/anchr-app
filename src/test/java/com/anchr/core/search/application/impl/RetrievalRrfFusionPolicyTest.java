package com.anchr.core.search.application.impl;

import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentHit;
import com.anchr.core.search.domain.model.SegmentRerankCandidate;
import com.anchr.core.search.domain.model.SegmentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalRrfFusionPolicyTest {

    private final RetrievalRrfFusionPolicy policy = new RetrievalRrfFusionPolicy();

    @Test
    void fuseShouldPreserveRrfFormulaSourcePreferenceAndTieBreakers() {
        SegmentHit textFirst = hit("s1", "asset-1", SegmentType.TEXT_CHUNK, 0.4D,
                Map.of("contentText", "<em>one</em>"));
        SegmentHit textSecond = hit("s2", "asset-2", SegmentType.TEXT_CHUNK, 0.7D,
                Map.of("title", "<em>two</em>"));
        SegmentHit vectorFirst = hit("s2", "asset-2", SegmentType.TEXT_CHUNK, 0.9D, Map.of());
        SegmentHit imageFirst = hit("s3", "asset-3", SegmentType.DOCUMENT_IMAGE, 0.8D, Map.of());

        List<SegmentRerankCandidate> fused = policy.fuse(
                List.of(textFirst, textSecond),
                List.of(vectorFirst),
                List.of(imageFirst),
                60);

        assertThat(fused).extracting(SegmentRerankCandidate::segmentId)
                .containsExactly("s2", "s3", "s1");
        SegmentRerankCandidate first = fused.getFirst();
        assertThat(first.score()).isEqualTo(1d / 62d + 1d / 61d);
        assertThat(first.bestRawScore()).isEqualTo(0.9D);
        assertThat(first.hitCount()).isEqualTo(2);
        assertThat(first.vectorHit()).isTrue();
        assertThat(first.highlights()).containsEntry("title", "<em>two</em>");
    }

    @Test
    void diversifyShouldKeepFirstThreePerAssetAndSegmentTypeWithoutReorderingOthers() {
        List<SegmentRerankCandidate> candidates = List.of(
                candidate("text-1", "asset-1", SegmentType.TEXT_CHUNK),
                candidate("text-2", "asset-1", SegmentType.TEXT_CHUNK),
                candidate("text-3", "asset-1", SegmentType.TEXT_CHUNK),
                candidate("text-4", "asset-1", SegmentType.TEXT_CHUNK),
                candidate("image-1", "asset-1", SegmentType.DOCUMENT_IMAGE),
                candidate("text-5", "asset-2", SegmentType.TEXT_CHUNK));

        assertThat(policy.diversify(candidates))
                .extracting(SegmentRerankCandidate::segmentId)
                .containsExactly("text-1", "text-2", "text-3", "image-1", "text-5");
    }

    private SegmentHit hit(
            String segmentId,
            String assetId,
            SegmentType type,
            double rawScore,
            Map<String, String> highlights
    ) {
        return SegmentHit.builder()
                .segment(Segment.builder()
                        .segmentId(segmentId)
                        .assetId(assetId)
                        .segmentType(type)
                        .build())
                .rawScore(rawScore)
                .highlights(highlights)
                .build();
    }

    private SegmentRerankCandidate candidate(
            String segmentId,
            String assetId,
            SegmentType type
    ) {
        return new SegmentRerankCandidate(
                segmentId,
                Segment.builder()
                        .segmentId(segmentId)
                        .assetId(assetId)
                        .segmentType(type)
                        .build(),
                Map.of(),
                1D,
                1D,
                1,
                false);
    }
}
