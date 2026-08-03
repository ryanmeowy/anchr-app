package com.anchr.core.conversation.application.assembler;

import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.domain.model.ConversationCitation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCitationMapperTest {

    @Test
    void mapAndCodec_shouldCarryDocumentChunkOrder() {
        ConversationRetrievalCandidate candidate = ConversationRetrievalCandidate.builder()
                .segmentId("seg-1")
                .assetId("asset-1")
                .sourceRef("docs/guide.pdf")
                .pageNo(3)
                .snippet("evidence")
                .anchor(ConversationRetrievalCandidate.Anchor.builder().chunkOrder(17).build())
                .build();

        var citations = new ConversationCitationMapper().mapFromSearchResults(List.of(candidate));
        ConversationTurnCodec codec = new ConversationTurnCodec(new ObjectMapper());
        var responseCitations = codec.parseCitations(codec.serializeCitations(citations));

        assertThat(citations).singleElement().extracting("chunkOrder").isEqualTo(17);
        assertThat(responseCitations).singleElement().satisfies(group -> {
            assertThat(group.getAssetId()).isEqualTo("asset-1");
            assertThat(group.getChunks()).singleElement().extracting("chunkOrder").isEqualTo(17);
        });
    }

    @Test
    void codec_shouldKeepOldCitationJsonCompatibleWhenChunkOrderIsMissing() {
        ConversationTurnCodec codec = new ConversationTurnCodec(new ObjectMapper());

        var citations = codec.parseCitations("[{\"segmentId\":\"old-seg\",\"assetId\":\"asset-1\",\"pageNo\":2}]");

        assertThat(citations).singleElement().satisfies(citation -> {
            assertThat(citation.getChunks()).singleElement().satisfies(chunk -> {
                assertThat(chunk.getSegmentId()).isEqualTo("old-seg");
                assertThat(chunk.getChunkOrder()).isNull();
            });
        });
    }

    @Test
    void documentImageCitationShouldKeepTheParentDocumentTitle() {
        ConversationRetrievalCandidate candidate = ConversationRetrievalCandidate.builder()
                .segmentId("image-1")
                .assetId("asset-1")
                .segmentType("DOCUMENT_IMAGE")
                .title("guide.pdf")
                .sourceRef("embedded/diagram.png")
                .build();

        var citations = new ConversationCitationMapper()
                .mapFromSearchResults(List.of(candidate));

        assertThat(citations).singleElement()
                .extracting(ConversationCitation::getFileName)
                .isEqualTo("guide.pdf");
    }

    @Test
    void codec_shouldGroupMultipleUsedChunksByAssetAndSortThemInDocumentOrder() {
        ConversationTurnCodec codec = new ConversationTurnCodec(new ObjectMapper());

        var citations = codec.parseCitations("""
                [{"segmentId":"seg-2","assetId":"asset-1","fileName":"guide.pdf","pageNo":5,"chunkOrder":20},
                 {"segmentId":"seg-1","assetId":"asset-1","fileName":"guide.pdf","pageNo":2,"chunkOrder":4},
                 {"segmentId":"seg-3","assetId":"asset-2","fileName":"other.pdf","pageNo":1,"chunkOrder":1}]
                """);

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).getCitationIndex()).isEqualTo(1);
        assertThat(citations.get(0).getAssetId()).isEqualTo("asset-1");
        assertThat(citations.get(0).getChunks()).extracting("segmentId")
                .containsExactly("seg-1", "seg-2");
        assertThat(citations.get(1).getCitationIndex()).isEqualTo(2);
    }

    @Test
    void codec_shouldPersistAgentSegmentIndexesAndExposeStableLabels() {
        ConversationTurnCodec codec = new ConversationTurnCodec(new ObjectMapper());
        ConversationCitation first = citation("seg-2", "asset-1", 1, 1);
        ConversationCitation second = citation("seg-1", "asset-1", 1, 2);

        var restored = codec.parseCitations(codec.serializeCitations(List.of(first, second)));

        assertThat(restored).singleElement().satisfies(group -> {
            assertThat(group.getCitationIndex()).isEqualTo(1);
            assertThat(group.getChunks()).extracting("segmentId", "segmentIndex", "citationLabel")
                    .containsExactly(
                            Tuple.tuple("seg-2", 1, "1-1"),
                            Tuple.tuple("seg-1", 2, "1-2"));
        });
    }

    private ConversationCitation citation(String segmentId, String assetId, int assetIndex, int segmentIndex) {
        ConversationCitation citation = new ConversationCitation();
        citation.setSegmentId(segmentId);
        citation.setAssetId(assetId);
        citation.setAssetCitationIndex(assetIndex);
        citation.setSegmentCitationIndex(segmentIndex);
        return citation;
    }
}
