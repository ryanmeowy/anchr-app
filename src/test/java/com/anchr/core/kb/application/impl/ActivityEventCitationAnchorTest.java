package com.anchr.core.kb.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.model.BboxInfo;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.kb.application.ActivityEventService;
import com.anchr.core.kb.domain.model.ActivityEvent;
import com.anchr.core.kb.domain.model.ActivityEventType;
import com.anchr.core.kb.domain.repository.ActivityEventRepository;
import com.anchr.core.search.interfaces.rest.dto.PreviewAnchorDTO;
import com.anchr.core.search.interfaces.rest.dto.CitationChunkSnapshotDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityEventCitationAnchorTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void recordCitationOpened_shouldPersistCompleteAnchorInPayload() throws Exception {
        ActivityEventRepository repository = mock(ActivityEventRepository.class);
        IdGen idGen = mock(IdGen.class);
        when(idGen.nextIdStr()).thenReturn("event-1");
        ObjectMapper objectMapper = new ObjectMapper();
        ActivityEventServiceImpl service = new ActivityEventServiceImpl(repository, idGen, objectMapper);
        UserContextHolder.set(new RequestUserContext("user-1", "OWNER", "token"));
        PreviewAnchorDTO anchor = anchor(9, 42);

        service.recordCitationOpened(ActivityEventService.CitationContext.builder()
                .segmentId("seg-1")
                .assetId("asset-1")
                .citationReason("reason")
                .citationIndex("1")
                .anchor(anchor)
                .chunks(List.of(
                        CitationChunkSnapshotDTO.builder().segmentId("seg-1").content("content-1").anchor(anchor).build(),
                        CitationChunkSnapshotDTO.builder().segmentId("seg-2").content("content-2").anchor(anchor(10, 43)).build()
                ))
                .build());

        ArgumentCaptor<ActivityEvent> captor = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(repository).save(captor.capture());
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
    void fetchCitation_shouldRestoreCompleteAnchorFromPayload() throws Exception {
        ActivityEventRepository repository = mock(ActivityEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "segmentId", "seg-1",
                "anchor", anchor(7, 31),
                "chunks", List.of(
                        CitationChunkSnapshotDTO.builder().segmentId("seg-1").content("content-1").anchor(anchor(7, 31)).build(),
                        CitationChunkSnapshotDTO.builder().segmentId("seg-2").content("content-2").anchor(anchor(8, 32)).build()
                )
        ));
        when(repository.fetchByIdAndType("event-1", ActivityEventType.CITATION_OPENED))
                .thenReturn(ActivityEvent.builder()
                        .id("event-1")
                        .eventType(ActivityEventType.CITATION_OPENED)
                        .resourceId("seg-1")
                        .payload(payload)
                        .createdAt(LocalDateTime.now())
                        .build());
        ActivityQueryServiceImpl service = new ActivityQueryServiceImpl(repository, mock(com.anchr.core.kb.domain.repository.KnowledgeBaseRepository.class), objectMapper);

        var result = service.fetchCitationsById("event-1");

        assertThat(result.getAnchor().getPageNo()).isEqualTo(7);
        assertThat(result.getAnchor().getChunkOrder()).isEqualTo(31);
        assertThat(result.getAnchor().getBbox()).hasSize(1);
        assertThat(result.getAnchor().getBbox().getFirst().getBbox().getL()).isEqualTo(1D);
        assertThat(result.getAnchor().getImageWidth()).isEqualTo(1200);
        assertThat(result.getAnchor().getImageHeight()).isEqualTo(1600);
        assertThat(result.getChunks()).extracting(CitationChunkSnapshotDTO::getSegmentId)
                .containsExactly("seg-1", "seg-2");
        assertThat(result.getChunks().get(1).getAnchor().getPageNo()).isEqualTo(8);
    }

    private PreviewAnchorDTO anchor(int pageNo, int chunkOrder) {
        return PreviewAnchorDTO.builder()
                .pageNo(pageNo)
                .chunkOrder(chunkOrder)
                .bbox(List.of(BboxInfo.builder()
                        .pageNo(pageNo)
                        .bbox(BboxInfo.Bbox.builder().l(1).t(2).r(3).b(4).coordOrigin("TOPLEFT").build())
                        .build()))
                .imageWidth(1200)
                .imageHeight(1600)
                .build();
    }
}
