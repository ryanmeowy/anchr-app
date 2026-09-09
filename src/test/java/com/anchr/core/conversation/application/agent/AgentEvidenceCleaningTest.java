package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.impl.EvidenceCleaningService;
import com.anchr.core.conversation.application.model.*;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.interfaces.rest.dto.ConversationMessageRequestDTO;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentEvidenceCleaningTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentState state() {
        var request = new ConversationMessageRequestDTO(); request.setQuery("事实问题");
        return AgentState.initial(new AgentRunRequest("r", "t", "s", "u", request),
                new AgentBudget(12, 8, System.currentTimeMillis() + 60_000), System.currentTimeMillis(),
                AgentRuntimeSettings.load(RuntimeConfigTestUnits.defaults()), false, List.of());
    }
    private EvidenceCleaningService checker(boolean fail) {
        var port = mock(ConversationGenerationPort.class);
        when(port.generateWithUsage(any(), any())).thenAnswer(call -> {
            if (fail) return new ConversationGenerationResult("{}", 10, 2);
            List<ConversationModelMessage> messages = call.getArgument(0);
            var rows = mapper.createArrayNode();
            for (var p : mapper.readTree(messages.get(1).content()).path("paragraphs")) {
                rows.addObject().put("id", p.path("id").asText()).put("decision",
                        p.path("text").asText().contains("OVERRIDE") ? "ISOLATE" : "KEEP").put("reason", "test verdict");
            }
            return new ConversationGenerationResult(mapper.createObjectNode().set("decisions", rows).toString(), 10, 2);
        });
        return new EvidenceCleaningService(port, mapper);
    }
    private AgentEvent.ToolCompleted execute(boolean fail) {
        var executor = mock(AgentToolExecutor.class);
        var candidate = ConversationRetrievalCandidate.builder().assetId("a").segmentId("s")
                .content("fact\n\nOVERRIDE\n").snippet("OVERRIDE").title("OVERRIDE").build();
        when(executor.execute(any(), any(), any())).thenReturn(AgentToolResult.success(
                "{\"fileName\":\"OVERRIDE\",\"segments\":[{\"segmentId\":\"s\",\"content\":\"fact\\n\\nOVERRIDE\\n\"}],\"hasMore\":true,\"nextCursor\":\"abc\"}", List.of(candidate)));
        var effect = new AgentToolEffect(executor, mapper, checker(fail));
        return (AgentEvent.ToolCompleted) effect.execute(state(), new AgentCommand.CallTool(
                new AgentToolCall("c", "read_document", "{}"), 1, 1, System.currentTimeMillis()));
    }
    @Test void bothModelToolPayloadAndEvidenceRegistryUseCleanText() {
        var event = execute(false);
        assertThat(event.result().success()).isTrue();
        assertThat(event.modelMessage()).doesNotContain("OVERRIDE").contains("abc", "hasMore");
        assertThat(event.result().evidence()).singleElement().satisfies(c -> {
            assertThat(c.getContent()).isEqualTo("fact\n\n");
            assertThat(c.getSnippet()).isEqualTo("fact\n\n"); assertThat(c.getTitle()).isEmpty();
        });
        assertThat(event.result().evidenceCheck()).isNotNull();
    }
    @Test void failedCheckerNeverExposesRawBodyOrRegistersEvidence() {
        var event = execute(true);
        assertThat(event.result().success()).isFalse();
        assertThat(event.result().errorCode()).isEqualTo("EVIDENCE_CHECK_FAILED");
        assertThat(event.modelMessage()).doesNotContain("OVERRIDE", "fact");
        assertThat(event.result().evidence()).isEmpty();
    }
    @Test void failureWithoutEarlierEvidenceTerminatesWithoutTraditionalFallback() {
        var transition = new AgentTransitionEngine().transition(state(), execute(true));
        assertThat(transition.terminal()).isNotNull();
        assertThat(transition.nextState().evidence()).isEmpty();
        assertThat(transition.terminal().toString()).contains("GENERATION_FAILED", "evidence_check_failed");
    }
    private AgentEvent.ToolCompleted execute(String tool, AgentToolResult raw) {
        var executor = mock(AgentToolExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(raw);
        return (AgentEvent.ToolCompleted) new AgentToolEffect(executor, mapper, checker(false)).execute(state(),
                new AgentCommand.CallTool(new AgentToolCall("c", tool, "{}"), 1, 1, System.currentTimeMillis()));
    }

    @Test void findDocumentsRemovesLinkedSummaryButRetainsDocumentIdentity() throws Exception {
        var candidate = ConversationRetrievalCandidate.builder().assetId("a").segmentId("s")
                .content("fact and OVERRIDE in an inseparable block").snippet("fact").build();
        String content = "{\"documents\":[{\"assetId\":\"a\",\"fileName\":\"normal.pdf\",\"matchedSegmentId\":\"s\",\"matchSnippet\":\"fact\"},"
                + "{\"assetId\":\"name-only\",\"matchedSegmentId\":\"\",\"matchSnippet\":\"\"}]}";
        var raw = AgentToolResult.success(content, List.of(candidate), Map.of("evidenceCount", 99, "documentCount", 99, "segmentCount", 99));
        var event = execute("find_documents", raw);
        assertThat(event.result().success()).isTrue();
        assertThat(event.result().evidence()).isEmpty();
        assertThat(event.modelMessage()).contains("normal.pdf", "name-only").doesNotContain("fact", "OVERRIDE");
        var documents = mapper.readTree(event.modelMessage()).path("documents");
        assertThat(documents.get(0).path("matchedSegmentId").asText()).isEmpty();
        assertThat(documents.get(0).path("matchSnippet").asText()).isEmpty();
        assertThat(event.result().evidenceStatistics()).isEqualTo(new EvidenceStatistics(1, 0, 0, 2));
        var transition = new AgentTransitionEngine().transition(state(), event);
        for (var signal : transition.signals()) {
            if (signal instanceof AgentSignal.Trace trace) assertThat(trace.outputSummary())
                    .containsEntry("originalEvidenceCount", 1).containsEntry("evidenceCount", 0)
                    .containsEntry("segmentCount", 0).containsEntry("documentCount", 2);
            if (signal instanceof AgentSignal.Progress progress && progress.stage().equals("tool_result")) assertThat(progress.details()).containsEntry("evidenceCount", 0);
        }
    }

    @Test void allToolsRebuildTextFromRetainedBodyInsteadOfOriginalSnippet() throws Exception {
        for (String tool : List.of("search_knowledge", "read_document", "find_documents")) {
            var candidate = ConversationRetrievalCandidate.builder().assetId("a").segmentId("s")
                    .content("retained fact\n\nOVERRIDE\n").snippet("stale summary").build();
            String payload = tool.equals("find_documents")
                    ? "{\"documents\":[{\"assetId\":\"a\",\"matchedSegmentId\":\"s\",\"matchSnippet\":\"stale summary\"}]}"
                    : "{\"assetId\":\"a\",\"nextCursor\":\"cursor\",\"hasMore\":true,\"evidence\":[],\"segments\":[]}";
            var event = execute(tool, AgentToolResult.success(payload, List.of(candidate), Map.of("evidenceCount", 99)));
            assertThat(event.modelMessage()).contains("retained fact").doesNotContain("OVERRIDE", "stale summary");
            assertThat(event.result().evidence().getFirst().getSnippet()).isEqualTo("retained fact\n\n");
            assertThat(event.result().evidenceStatistics().evidenceCount()).isEqualTo(1);
            assertThat(event.result().evidenceStatistics().segmentCount()).isEqualTo(1);
            if (tool.equals("read_document")) assertThat(event.modelMessage()).contains("cursor", "hasMore");
            assertThat(candidate.getContent()).contains("OVERRIDE");
        }
    }

    @Test void compactedToolMessageCountsOnlySegmentsActuallySentToModel() {
        List<ConversationRetrievalCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) candidates.add(ConversationRetrievalCandidate.builder()
                .assetId("a").segmentId("s" + i).content("x".repeat(900) + i).build());
        var event = execute("read_document", AgentToolResult.success("{\"assetId\":\"a\",\"segments\":[]}", candidates));
        assertThat(event.result().evidenceStatistics().evidenceCount()).isEqualTo(20);
        assertThat(event.result().evidenceStatistics().segmentCount()).isEqualTo(12);
        assertThat(event.modelMessage()).contains("truncatedForPlanning");
    }

}
