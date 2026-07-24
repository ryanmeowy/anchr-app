package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.AssetIndexChange;
import com.anchr.core.kb.domain.model.AssetIndexChangeOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetIndexChangeRepositoryImplTest {

    @Mock
    private AssetIndexChangeMapper mapper;

    private AssetIndexChangeRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new AssetIndexChangeRepositoryImpl(mapper);
    }

    @Test
    void save_shouldValidateAndMapEveryField() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 24, 12, 30);
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        AssetIndexChange change = AssetIndexChange.builder()
                .eventId(" event-1 ")
                .kbId(" kb-1 ")
                .assetId(" asset-1 ")
                .operation(AssetIndexChangeOperation.GENERATION_ACTIVATED)
                .indexGeneration(3)
                .occurredAt(occurredAt)
                .createdBy(" user-a ")
                .build();

        repository.save(change);

        ArgumentCaptor<AssetIndexChangeRecord> captor =
                ArgumentCaptor.forClass(AssetIndexChangeRecord.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue())
                .extracting(
                        AssetIndexChangeRecord::getEventId,
                        AssetIndexChangeRecord::getKbId,
                        AssetIndexChangeRecord::getAssetId,
                        AssetIndexChangeRecord::getOperation,
                        AssetIndexChangeRecord::getIndexGeneration,
                        AssetIndexChangeRecord::getOccurredAt,
                        AssetIndexChangeRecord::getCreatedBy)
                .containsExactly(
                        "event-1",
                        "kb-1",
                        "asset-1",
                        "GENERATION_ACTIVATED",
                        3L,
                        occurredAt,
                        "user-a");
    }

    @Test
    void save_shouldRejectInvalidBusinessValuesBeforeCallingMapper() {
        AssetIndexChange valid = validChange();

        assertThatThrownBy(() -> repository.save(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.save(valid.toBuilder().revision(1L).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.save(valid.toBuilder().eventId(" ").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.save(valid.toBuilder().operation(null).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.save(
                valid.toBuilder().indexGeneration(-1).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.save(valid.toBuilder().occurredAt(null).build()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listAfterRevision_shouldMapRowsInMapperOrder() {
        AssetIndexChangeRecord first = record(
                7L, "event-7", "GENERATION_ACTIVATED", 2L);
        AssetIndexChangeRecord second = record(
                8L, "event-8", "ASSET_DELETED", 2L);
        when(mapper.listAfterRevision(6L, 20)).thenReturn(List.of(first, second));

        List<AssetIndexChange> changes = repository.listAfterRevision(6L, 20);

        verify(mapper).listAfterRevision(6L, 20);
        assertThat(changes)
                .extracting(AssetIndexChange::getRevision)
                .containsExactly(7L, 8L);
        assertThat(changes)
                .extracting(AssetIndexChange::getOperation)
                .containsExactly(
                        AssetIndexChangeOperation.GENERATION_ACTIVATED,
                        AssetIndexChangeOperation.ASSET_DELETED);
        assertThat(changes)
                .extracting(AssetIndexChange::getEventId)
                .containsExactly("event-7", "event-8");
    }

    @Test
    void listAfterRevision_shouldRejectInvalidPageArguments() {
        assertThatThrownBy(() -> repository.listAfterRevision(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exclusiveRevision");
        assertThatThrownBy(() -> repository.listAfterRevision(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        verify(mapper, never()).listAfterRevision(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void listAfterRevision_shouldRejectCorruptPersistedOperation() {
        when(mapper.listAfterRevision(0L, 10))
                .thenReturn(List.of(record(1L, "event-1", "BROKEN", 0L)));

        assertThatThrownBy(() -> repository.listAfterRevision(0, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid operation");
    }

    @Test
    void save_shouldRejectUnexpectedInsertCount() {
        when(mapper.insert(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        assertThatThrownBy(() -> repository.save(validChange()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not persisted");
    }

    private AssetIndexChange validChange() {
        return AssetIndexChange.builder()
                .eventId("event-1")
                .kbId("kb-1")
                .assetId("asset-1")
                .operation(AssetIndexChangeOperation.GENERATION_ACTIVATED)
                .indexGeneration(1)
                .occurredAt(LocalDateTime.now())
                .createdBy("user-a")
                .build();
    }

    private AssetIndexChangeRecord record(
            long revision, String eventId, String operation, long indexGeneration) {
        AssetIndexChangeRecord record = new AssetIndexChangeRecord();
        record.setRevision(revision);
        record.setEventId(eventId);
        record.setKbId("kb-1");
        record.setAssetId("asset-1");
        record.setOperation(operation);
        record.setIndexGeneration(indexGeneration);
        record.setOccurredAt(LocalDateTime.of(2026, 7, 24, 12, 30));
        record.setCreatedBy("user-a");
        return record;
    }
}
