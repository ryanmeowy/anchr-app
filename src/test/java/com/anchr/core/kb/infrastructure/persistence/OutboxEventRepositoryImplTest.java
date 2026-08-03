package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.OutboxEvent;
import com.anchr.core.kb.domain.model.OutboxEventStatus;
import com.anchr.core.kb.domain.model.OutboxEventType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxEventRepositoryImplTest {

    private final OutboxEventMapper mapper = mock(OutboxEventMapper.class);
    private final OutboxEventRepositoryImpl repository =
            new OutboxEventRepositoryImpl(mapper);

    @Test
    void claimAvailable_shouldMarkSelectedRowsWithTheSameLeaseToken() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 29, 18, 0);
        LocalDateTime expiredBefore = now.minusMinutes(5);
        OutboxEventRecord first = record(1L, "asset-1");
        OutboxEventRecord second = record(2L, "asset-2");
        when(mapper.selectClaimableForUpdate(now, expiredBefore, 20))
                .thenReturn(List.of(first, second));

        var claimed = repository.claimAvailable(
                now, expiredBefore, 20, "worker-token");

        verify(mapper).markProcessing(1L, "worker-token", now);
        verify(mapper).markProcessing(2L, "worker-token", now);
        assertThat(claimed).hasSize(2);
        assertThat(claimed).allSatisfy(event -> {
            assertThat(event.getStatus())
                    .isEqualTo(OutboxEventStatus.PROCESSING);
            assertThat(event.getLockToken()).isEqualTo("worker-token");
            assertThat(event.getLockedAt()).isEqualTo(now);
        });
    }

    @Test
    void terminalUpdates_shouldAlwaysForwardTheClaimToken() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 29, 18, 0);
        LocalDateTime retryAt = now.plusMinutes(1);
        when(mapper.markDone(1L, "claim-1", now)).thenReturn(1);
        when(mapper.markRetry(
                2L, "claim-2", 3, retryAt, "retry", now)).thenReturn(1);
        when(mapper.markFailed(
                3L, "claim-3", 10, "failed", now)).thenReturn(1);

        assertThat(repository.markDone(1L, "claim-1", now)).isTrue();
        assertThat(repository.markRetry(
                2L, "claim-2", 3, retryAt, "retry", now)).isTrue();
        assertThat(repository.markFailed(
                3L, "claim-3", 10, "failed", now)).isTrue();

        verify(mapper).markDone(1L, "claim-1", now);
        verify(mapper).markRetry(
                2L, "claim-2", 3, retryAt, "retry", now);
        verify(mapper).markFailed(
                3L, "claim-3", 10, "failed", now);
    }

    @Test
    void save_shouldKeepPersistedTypePayloadAndAggregateIdentity() {
        var event = OutboxEvent.builder()
                .eventType(OutboxEventType.DELETE_ASSET_GENERATION)
                .aggregateType("ASSET")
                .aggregateId("asset-1")
                .payload("{\"kbId\":\"kb-1\",\"assetId\":\"asset-1\","
                        + "\"indexGeneration\":4}")
                .status(OutboxEventStatus.PENDING)
                .retryCount(0)
                .build();
        ArgumentCaptor<OutboxEventRecord> record =
                ArgumentCaptor.forClass(OutboxEventRecord.class);

        repository.save(event);

        verify(mapper).insert(record.capture());
        assertThat(record.getValue().getEventType())
                .isEqualTo("DELETE_ASSET_GENERATION");
        assertThat(record.getValue().getAggregateType()).isEqualTo("ASSET");
        assertThat(record.getValue().getAggregateId()).isEqualTo("asset-1");
        assertThat(record.getValue().getPayload()).isEqualTo(event.getPayload());
    }

    private OutboxEventRecord record(long id, String assetId) {
        OutboxEventRecord record = new OutboxEventRecord();
        record.setId(id);
        record.setEventType(OutboxEventType.DELETE_ASSET.name());
        record.setAggregateType("ASSET");
        record.setAggregateId(assetId);
        record.setPayload("{\"kbId\":\"kb-1\",\"assetId\":\""
                + assetId + "\"}");
        record.setStatus(OutboxEventStatus.PENDING.name());
        record.setRetryCount(0);
        return record;
    }
}
