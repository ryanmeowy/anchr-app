package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.domain.model.IngestionAttemptArtifactDeletePayload;
import com.anchr.core.ingestion.domain.model.IngestionClaimTransition;
import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventType;
import com.anchr.core.kb.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestionArtifactCleanupRecorderTest {

    @Mock private OutboxEventRepository outboxEventRepository;

    @Test
    void terminalFailureRecordsOnlyTheOwnedAttemptPrefixes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IngestionArtifactCleanupRecorder recorder = new IngestionArtifactCleanupRecorder(
                outboxEventRepository, objectMapper);
        String snapshot = "{\"artifactVersion\":1,\"contractVersion\":3,"
                + "\"fileName\":\"document.pdf\",\"options\":{},"
                + "\"ossTarget\":{\"endpoint\":\"oss.example\","
                + "\"bucket\":\"bucket-a\","
                + "\"basePath\":\"embedded/ingestion/task-1/item-1/parse/2/images/\","
                + "\"objectKeyLayout\":\"ATTEMPT_PREFIX_V1\"}}";
        IngestionClaimTransition transition = IngestionClaimTransition.builder()
                .taskId("task-1")
                .itemId("item-1")
                .executionEpoch(4L)
                .parseAttempt(2)
                .parseRequestSnapshot(snapshot)
                .updatedBy("user-a")
                .build();

        recorder.terminalFailure(transition);

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(event.capture());
        assertThat(event.getValue().getEventType())
                .isEqualTo(OutboxEventType.DELETE_INGESTION_ATTEMPT_ARTIFACTS);
        assertThat(event.getValue().getAggregateId()).isEqualTo("item-1");
        IngestionAttemptArtifactDeletePayload payload = objectMapper.readValue(
                event.getValue().getPayload(), IngestionAttemptArtifactDeletePayload.class);
        assertThat(payload.imagePrefix())
                .isEqualTo("embedded/ingestion/task-1/item-1/parse/2/images/");
        assertThat(payload.parseArtifactPrefix())
                .isEqualTo("ingestion/task-1/item-1/parse/2/");
    }
}
