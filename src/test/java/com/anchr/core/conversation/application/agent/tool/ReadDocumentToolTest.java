package com.anchr.core.conversation.application.agent.tool;

import com.anchr.core.conversation.application.agent.AgentBudget;
import com.anchr.core.conversation.application.agent.AgentExecutionContext;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.repository.SegmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadDocumentToolTest {

    @Test
    void execute_shouldCarryAssetFileNameIntoCitationEvidence() {
        AgentScopeGuard scopeGuard = mock(AgentScopeGuard.class);
        SegmentRepository segments = mock(SegmentRepository.class);
        ReadDocumentTool tool = new ReadDocumentTool(scopeGuard, segments, new ObjectMapper());
        AgentExecutionContext context = new AgentExecutionContext(
                "run-1", "turn-1", "session-1", "user-1",
                List.of("kb-1"), List.of("asset-1"),
                new AgentBudget(12, 8, System.currentTimeMillis() + 10_000));
        Asset asset = Asset.builder().id("asset-1").kbId("kb-1")
                .fileName("Corrective Retrieval Augmented Generation.pdf")
                .title("CRAG").build();
        Segment segment = Segment.builder().segmentId("seg-1").kbId("kb-1").assetId("asset-1")
                .segmentType(SegmentType.TEXT_CHUNK).contentText("不同置信度对应不同动作")
                .pageNo(3).chunkOrder(7).build();
        when(scopeGuard.requireAsset("asset-1", context)).thenReturn(asset);
        when(segments.listByAssetId("kb-1", "asset-1", null, null, 21)).thenReturn(List.of(segment));

        var result = tool.execute(new ReadDocumentTool.Input("asset-1", null, 20), context);

        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.getSourceRef()).isEqualTo("Corrective Retrieval Augmented Generation.pdf");
            assertThat(evidence.getAssetId()).isEqualTo("asset-1");
        });
    }
}
