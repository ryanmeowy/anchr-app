package com.anchr.core.conversation.application.assembler;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.interfaces.rest.dto.ResultCardDTO;
import com.anchr.core.conversation.interfaces.rest.dto.ResultHitDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationResultCardMapperTest {

    private final ConversationResultCardMapper mapper = new ConversationResultCardMapper();

    @Test
    void map_shouldGroupByAssetAndKeepTopThreeCards() {
        List<ConversationRetrievalCandidate> candidates = List.of(
                candidate("seg_a_1", "asset_a", "PDF", "TEXT_CHUNK", "oss://bucket/a.pdf", "a primary", 0.92d, 3, 12),
                candidate("seg_b_1", "asset_b", "IMAGE", "IMAGE_OCR_BLOCK", "oss://bucket/b.png", "b primary", 0.91d, null, null),
                candidate("seg_a_2", "asset_a", "PDF", "TEXT_CHUNK", "oss://bucket/a.pdf", "a additional 1", 0.88d, 5, 2),
                candidate("seg_a_3", "asset_a", "PDF", "TEXT_CHUNK", "oss://bucket/a.pdf", "a additional 2", 0.87d, 7, 4),
                candidate("seg_a_4", "asset_a", "PDF", "TEXT_CHUNK", "oss://bucket/a.pdf", "a additional 3", 0.86d, 9, 6),
                candidate("seg_c_1", "asset_c", "TXT", "TEXT_CHUNK", "oss://bucket/c.txt", "c primary", 0.80d, null, 0),
                candidate("seg_d_1", "asset_d", "MD", "TEXT_CHUNK", "oss://bucket/d.md", "d primary", 0.70d, null, 1)
        );

        List<ResultCardDTO> cards = mapper.map(candidates);

        assertThat(cards).hasSize(3);
        assertThat(cards).extracting(ResultCardDTO::getAssetId)
                .containsExactly("asset_a", "asset_b", "asset_c");
        ResultCardDTO firstCard = cards.getFirst();
        assertThat(firstCard.getScore()).isEqualTo(0.92d);
        assertThat(firstCard.getHitCount()).isEqualTo(4);
        assertThat(firstCard.getFileName()).isEqualTo("a.pdf");
        assertThat(firstCard.getPrimaryHit().getSegmentId()).isEqualTo("seg_a_1");
        assertThat(firstCard.getPrimaryHit().getAnchor().getPageNo()).isEqualTo(3);
        assertThat(firstCard.getPrimaryHit().getAnchor().getChunkOrder()).isEqualTo(12);
        assertThat(firstCard.getAdditionalHits()).hasSize(2);
        assertThat(firstCard.getAdditionalHits()).extracting(ResultHitDTO::getSegmentId)
                .containsExactly("seg_a_2", "seg_a_3");

        ResultCardDTO imageCard = cards.get(1);
        assertThat(imageCard.getPrimaryHit().getAnchor().getBbox().getUnit()).isEqualTo("PIXEL");
        assertThat(imageCard.getPrimaryHit().getAnchor().getImageWidth()).isEqualTo(1200);
        assertThat(imageCard.getPrimaryHit().getAnchor().getImageHeight()).isEqualTo(900);
    }

    @Test
    void map_shouldUseDeterministicTieBreakAndSkipInvalidCandidates() {
        List<ConversationRetrievalCandidate> cards = List.of(
                candidate("seg_b", "asset_b", "PDF", "TEXT_CHUNK", "oss://bucket/b.pdf", "b", 0.80d, 1, 0),
                candidate("seg_a", "asset_a", "PDF", "TEXT_CHUNK", "oss://bucket/a.pdf", "a", 0.80d, 1, 0),
                candidate(null, "asset_invalid", "PDF", "TEXT_CHUNK", "oss://bucket/invalid.pdf", "invalid", 1.0d, 1, 0),
                candidate("seg_missing_asset", null, "PDF", "TEXT_CHUNK", "oss://bucket/invalid.pdf", "invalid", 1.0d, 1, 0)
        );

        List<ResultCardDTO> result = mapper.map(cards);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ResultCardDTO::getAssetId)
                .containsExactly("asset_a", "asset_b");
    }

    private ConversationRetrievalCandidate candidate(String segmentId,
                                                     String assetId,
                                                     String assetType,
                                                     String segmentType,
                                                     String sourceRef,
                                                     String snippet,
                                                     Double score,
                                                     Integer pageNo,
                                                     Integer chunkOrder) {
        return ConversationRetrievalCandidate.builder()
                .segmentId(segmentId)
                .assetId(assetId)
                .assetType(assetType)
                .segmentType(segmentType)
                .sourceRef(sourceRef)
                .snippet(snippet)
                .score(score)
                .pageNo(pageNo)
                .anchor(ConversationRetrievalCandidate.Anchor.builder()
                        .pageNo(pageNo)
                        .chunkOrder(chunkOrder)
                        .bbox("IMAGE".equals(assetType) ? ConversationRetrievalCandidate.Bbox.builder()
                                .x(10)
                                .y(20)
                                .width(300)
                                .height(120)
                                .unit("PIXEL")
                                .build() : null)
                        .imageWidth("IMAGE".equals(assetType) ? 1200 : null)
                        .imageHeight("IMAGE".equals(assetType) ? 900 : null)
                        .build())
                .build();
    }
}
