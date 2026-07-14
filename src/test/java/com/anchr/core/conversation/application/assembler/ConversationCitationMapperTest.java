package com.anchr.core.conversation.application.assembler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
