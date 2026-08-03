package com.anchr.core.activity.application.impl;

import com.anchr.core.activity.application.acl.ActivityKnowledgeAcl;
import com.anchr.core.activity.application.api.model.ActivityAnchor;
import com.anchr.core.activity.application.api.model.ActivityCitationChunk;
import com.anchr.core.activity.application.api.model.ActivityRecordCommand;
import com.anchr.core.activity.domain.model.ActivityEvent;
import com.anchr.core.activity.domain.model.ActivityEventType;
import com.anchr.core.activity.domain.repository.ActivityEventRepository;
import com.anchr.core.common.util.IdGen;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityEventCitationAnchorTest {

    @Test
    void recordCitationOpened_shouldPersistCompleteAnchorInPayloadAndUseCallerSnapshot() throws Exception {
        ActivityEventRepository repository = mock(ActivityEventRepository.class);
        IdGen idGen = mock(IdGen.class);
        when(idGen.nextIdStr()).thenReturn("event-1");
        ObjectMapper objectMapper = new ObjectMapper();
        ActivityRecordServiceImpl service = new ActivityRecordServiceImpl(repository, idGen, objectMapper);
        ActivityAnchor anchor = anchor(9, 42);
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 29, 12, 0);

        service.recordCitationOpened(new ActivityRecordCommand.CitationOpened(
                "user-1", "seg-1", "asset-1", null, null, null, null, "reason", "1",
                null, null, null, null, anchor,
                List.of(
                        new ActivityCitationChunk("seg-1", null, null, null, null, null,
                                "content-1", null, null, anchor, null),
                        new ActivityCitationChunk("seg-2", null, null, null, null, null,
                                "content-2", null, null, anchor(10, 43), null)),
                occurredAt));

        ArgumentCaptor<ActivityEvent> captor = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(occurredAt);
        var root = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(root.path("anchor").path("pageNo").asInt()).isEqualTo(9);
        assertThat(root.path("anchor").path("chunkOrder").asInt()).isEqualTo(42);
        assertThat(root.path("anchor").path("bbox").get(0).path("bbox").path("l").asDouble()).isEqualTo(1D);
        assertThat(root.path("anchor").path("imageWidth").asInt()).isEqualTo(1200);
        assertThat(root.path("anchor").path("imageHeight").asInt()).isEqualTo(1600);
        assertThat(root.path("chunks")).hasSize(2);
        assertThat(root.path("chunks").get(1).path("segmentId").asText()).isEqualTo("seg-2");
        assertThat(root.path("chunks").get(1).path("anchor").path("pageNo").asInt()).isEqualTo(10);
    }

    @Test
    void findCitation_shouldRestoreCompleteActivityOwnedSnapshot() throws Exception {
        ActivityEventRepository repository = mock(ActivityEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        String payload = objectMapper.writeValueAsString(Map.of(
                "segmentId", "seg-1",
                "anchor", anchor(7, 31),
                "chunks", List.of(
                        new ActivityCitationChunk("seg-1", null, null, null, null, null,
                                "content-1", null, null, anchor(7, 31), null),
                        new ActivityCitationChunk("seg-2", null, null, null, null, null,
                                "content-2", null, null, anchor(8, 32), null))));
        when(repository.fetchByIdAndType("event-1", ActivityEventType.CITATION_OPENED))
                .thenReturn(ActivityEvent.builder()
                        .id("event-1").eventType(ActivityEventType.CITATION_OPENED)
                        .resourceId("seg-1").payload(payload).createdAt(LocalDateTime.now()).build());
        ActivityQueryServiceImpl service = new ActivityQueryServiceImpl(
                repository, mock(ActivityKnowledgeAcl.class), objectMapper);

        var result = service.findCitationById("event-1").orElseThrow();

        assertThat(result.anchor().pageNo()).isEqualTo(7);
        assertThat(result.anchor().chunkOrder()).isEqualTo(31);
        assertThat(result.anchor().bbox()).hasSize(1);
        assertThat(result.anchor().bbox().getFirst().bbox().l()).isEqualTo(1D);
        assertThat(result.anchor().imageWidth()).isEqualTo(1200);
        assertThat(result.anchor().imageHeight()).isEqualTo(1600);
        assertThat(result.chunks()).extracting(ActivityCitationChunk::segmentId)
                .containsExactly("seg-1", "seg-2");
        assertThat(result.chunks().get(1).anchor().pageNo()).isEqualTo(8);
    }

    @Test
    void synchronousDeleteFailureIsNotSwallowed() {
        ActivityEventRepository repository = mock(ActivityEventRepository.class);
        doThrow(new IllegalStateException("delete unavailable"))
                .when(repository).deleteBySessionId("session-1");
        ActivityRecordServiceImpl service = new ActivityRecordServiceImpl(
                repository, mock(IdGen.class), new ObjectMapper());

        assertThatThrownBy(() -> service.deleteBySessionId("session-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("delete unavailable");
    }

    private ActivityAnchor anchor(int pageNo, int chunkOrder) {
        return new ActivityAnchor(pageNo, chunkOrder,
                List.of(new ActivityAnchor.ActivityBbox(
                        new ActivityAnchor.Bbox(1, 2, 3, 4, "TOPLEFT"), pageNo)),
                1200, 1600);
    }
}
