package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.conversation.application.model.ConversationRetrievalCandidate;
import com.anchr.core.conversation.application.model.ConversationRetrievalResult;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.search.domain.model.SegmentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FindDocumentsToolVisualEvidenceTest {

    @Test
    void visualHitMayFindTheDocumentButMustNotBecomeEvidence() {
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("diagram.png")
                .fileType("IMAGE")
                .segmentCount(1)
                .build();
        AssetRepository assets = (AssetRepository) Proxy.newProxyInstance(
                AssetRepository.class.getClassLoader(),
                new Class<?>[]{AssetRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "listActive" -> List.of();
                    case "findActiveById" -> Optional.of(asset);
                    default -> throw new UnsupportedOperationException(
                            method.getName());
                });
        ConversationRetrievalResult retrieval = new ConversationRetrievalResult();
        retrieval.setTopCandidates(List.of(
                ConversationRetrievalCandidate.builder()
                        .segmentId("visual-1")
                        .kbId("kb-1")
                        .assetId("asset-1")
                        .segmentType(SegmentType.IMAGE_VISUAL.name())
                        .score(0.95D)
                        .build()));
        FindDocumentsTool tool = new FindDocumentsTool(
                assets,
                (query, limit, kbIds, modalities, assetIds) -> retrieval,
                new ObjectMapper());
        AgentExecutionContext context = new AgentExecutionContext(
                "run-1", "turn-1", "session-1", "user-1",
                List.of("kb-1"), List.of(), null);

        var result = tool.execute(
                new FindDocumentsTool.Input("diagram", 5),
                context);

        assertThat(result.content()).contains("asset-1");
        assertThat(result.evidence()).isEmpty();
        assertThat(result.traceDetails())
                .containsEntry("documentCount", 1)
                .containsEntry("evidenceCount", 0);
    }
}
