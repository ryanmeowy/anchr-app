package com.anchr.core.conversation.application.acl;

import com.anchr.core.conversation.application.model.ConversationCitationReasonRequest;
import com.anchr.core.conversation.application.model.ConversationDocumentReference;
import com.anchr.core.search.application.api.RetrievalCitationReasonApi;
import com.anchr.core.search.application.api.RetrievalDocumentContentQueryApi;
import com.anchr.core.search.application.api.RetrievalHitQueryApi;
import com.anchr.core.search.application.api.model.RetrievalAnchor;
import com.anchr.core.search.application.api.model.RetrievalCitationReasonRequest;
import com.anchr.core.search.application.api.model.RetrievalDocumentChunk;
import com.anchr.core.search.application.api.model.RetrievalDocumentContentQuery;
import com.anchr.core.search.application.api.model.RetrievalExplain;
import com.anchr.core.search.application.api.model.RetrievalHit;
import com.anchr.core.search.application.api.model.RetrievalHitQuery;
import com.anchr.core.search.application.api.model.RetrievalTopChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationRetrievalAclTest {

    @Mock
    private RetrievalHitQueryApi retrievalHitQueryApi;
    @Mock
    private RetrievalDocumentContentQueryApi retrievalDocumentContentQueryApi;
    @Mock
    private RetrievalCitationReasonApi retrievalCitationReasonApi;

    private ConversationRetrievalAcl orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ConversationRetrievalAcl(
                retrievalHitQueryApi,
                retrievalDocumentContentQueryApi,
                retrievalCitationReasonApi,
                new SimpleMeterRegistry()
        );
        Mockito.lenient().when(retrievalHitQueryApi.query(any(RetrievalHitQuery.class))).thenReturn(List.of(
                result("seg_text", "TEXT_CHUNK"),
                result("seg_image", "IMAGE_CAPTION")
        ));
    }

    @Test
    void retrieve_shouldKeepAllModalitiesWhenUserDoesNotRestrictThem() {
        var result = orchestrator.retrieve("RAG 架构", 10, List.of("kb_1"), List.of("MIXED"), null);

        assertThat(result.getTopCandidates())
                .extracting(candidate -> candidate.getSegmentId())
                .containsExactly("seg_text", "seg_image");
        assertThat(result.getTopCandidates().getFirst().getContent())
                .isEqualTo("original content seg_text");
        ArgumentCaptor<RetrievalHitQuery> queryCaptor = ArgumentCaptor.forClass(RetrievalHitQuery.class);
        verify(retrievalHitQueryApi).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().hitTypes()).isEmpty();
    }

    @Test
    void retrieve_shouldPushUserTextRestrictionIntoSearchQuery() {
        var result = orchestrator.retrieve("RAG 定义", 10, List.of("kb_1"), List.of("TEXT"), null);

        assertThat(result.getTopCandidates())
                .extracting(candidate -> candidate.getSegmentId())
                .containsExactly("seg_text", "seg_image");
        ArgumentCaptor<RetrievalHitQuery> queryCaptor = ArgumentCaptor.forClass(RetrievalHitQuery.class);
        verify(retrievalHitQueryApi).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().hitTypes()).containsExactly("TEXT_CHUNK");
    }

    @Test
    void retrieve_shouldExpandMultipleHitsFromTheSameDocumentIntoSegmentCandidates() {
        when(retrievalHitQueryApi.query(any(RetrievalHitQuery.class))).thenReturn(List.of(
                hit(null, null, "asset-1", "docs/guide.pdf", null, null, List.of(
                        topChunk("seg-later", 0.8D, 5, 20),
                        topChunk("seg-earlier", 0.9D, 2, 4)))));

        var result = orchestrator.retrieve("RAG", 10, List.of("kb-1"), List.of("MIXED"), null);

        assertThat(result.getTopCandidates())
                .extracting("segmentId", "assetId", "pageNo")
                .containsExactly(
                        Tuple.tuple("seg-earlier", "asset-1", 2),
                        Tuple.tuple("seg-later", "asset-1", 5));
        assertThat(result.getTopCandidates())
                .extracting(candidate -> candidate.getAnchor().getChunkOrder())
                .containsExactly(4, 20);
        assertThat(result.getTopCandidates()).extracting("title")
                .containsExactly("section seg-earlier", "section seg-later");
    }

    @Test
    void retrieveShouldKeepEachTopChunksOwnHitExplanation() {
        when(retrievalHitQueryApi.query(any(RetrievalHitQuery.class)))
                .thenReturn(List.of(hit(null, null, "asset-1", null,
                        new RetrievalExplain(List.of("VECTOR"), null, null, null), null,
                        List.of(new RetrievalTopChunk(
                                "ocr-1", null, "IMAGE_OCR_BLOCK", null, null, null,
                                new RetrievalExplain(List.of("OCR"), null, null, null),
                                0.8D, null, null, null, null, null, null, null)))));

        var result = orchestrator.retrieve(
                "diagram", 10, List.of("kb-1"), List.of("MIXED"), null);

        assertThat(result.getTopCandidates()).singleElement()
                .satisfies(candidate -> assertThat(
                        candidate.getExplain().getHitSources())
                        .containsExactly("OCR"));
    }

    @Test
    void readDocumentShouldTranslateProviderChunksWithoutLeakingRetrievalModels() {
        var document = new ConversationDocumentReference(
                "asset-1", "kb-1", "guide.pdf", "Guide", "PDF",
                "application/pdf", 7L, 2);
        when(retrievalDocumentContentQueryApi.query(any(RetrievalDocumentContentQuery.class)))
                .thenReturn(List.of(new RetrievalDocumentChunk(
                        "seg-1", "kb-1", "asset-1", 7L, "DOCUMENT", "TEXT_CHUNK",
                        "Section", "original text", 3, 9, List.of(), "storage/path.pdf")));

        var result = orchestrator.readDocument(document, 4, "seg-0", 20);

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.getSegmentId()).isEqualTo("seg-1");
            assertThat(candidate.getContent()).isEqualTo("original text");
            assertThat(candidate.getSourceRef()).isEqualTo("guide.pdf");
            assertThat(candidate.getAnchor().getChunkOrder()).isEqualTo(9);
        });
        ArgumentCaptor<RetrievalDocumentContentQuery> query =
                ArgumentCaptor.forClass(RetrievalDocumentContentQuery.class);
        verify(retrievalDocumentContentQueryApi).query(query.capture());
        assertThat(query.getValue()).isEqualTo(new RetrievalDocumentContentQuery(
                "kb-1", "asset-1", 7L, 4, "seg-0", 20));
    }

    @Test
    void citationReasonShouldBeTranslatedThroughProviderApi() {
        when(retrievalCitationReasonApi.generate(any())).thenReturn(Map.of("seg-1", "reason"));
        var request = new ConversationCitationReasonRequest(
                "question", "rewritten", "answer",
                List.of(new ConversationCitationReasonRequest.CitationGroup(
                        1, "asset-1", List.of(new ConversationCitationReasonRequest.CitationChunk(
                                "seg-1", "content", 0.8D, List.of("VECTOR"), "semantic")))));

        assertThat(orchestrator.generateCitationReasons(request))
                .containsExactlyEntriesOf(Map.of("seg-1", "reason"));
        ArgumentCaptor<RetrievalCitationReasonRequest> providerRequest =
                ArgumentCaptor.forClass(RetrievalCitationReasonRequest.class);
        verify(retrievalCitationReasonApi).generate(providerRequest.capture());
        assertThat(providerRequest.getValue().citations().getFirst().chunks().getFirst().segmentId())
                .isEqualTo("seg-1");
    }

    private RetrievalHit result(String segmentId, String segmentType) {
        return new RetrievalHit(
                segmentType, null, "original content " + segmentId, null, null,
                "evidence " + segmentId, null, 0.9D, null, null, null, null,
                null, List.of(), segmentId, null, null, null, null, null);
    }

    private RetrievalTopChunk topChunk(String segmentId, double score, int pageNo, int chunkOrder) {
        return new RetrievalTopChunk(
                segmentId, null, "TEXT_CHUNK", "section " + segmentId,
                "original content " + segmentId, "evidence " + segmentId,
                null, score, pageNo,
                new RetrievalAnchor(pageNo, chunkOrder, List.of(), null, null),
                "docs/guide.pdf", null, null, null, null);
    }

    private RetrievalHit hit(String segmentId,
                             String segmentType,
                             String assetId,
                             String sourceRef,
                             RetrievalExplain explain,
                             Double score,
                             List<RetrievalTopChunk> topChunks) {
        return new RetrievalHit(
                segmentType, null, null, null, null, null, null, score, explain, null,
                null, null, null, topChunks, segmentId, null, assetId, sourceRef, null, null);
    }
}
