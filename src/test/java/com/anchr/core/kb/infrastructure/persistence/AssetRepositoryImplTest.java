package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.DocumentAvailabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetRepositoryImplTest {

    @Mock
    private AssetMapper mapper;

    @Test
    void findActiveIndexGenerations_shouldBatchAndTreatLegacyNullAsZero() {
        AssetRecord legacy = new AssetRecord();
        legacy.setId("asset-1");
        AssetRecord current = new AssetRecord();
        current.setId("asset-2");
        current.setActiveIndexGeneration(7L);
        when(mapper.findActiveIndexGenerations(List.of("asset-1", "asset-2")))
                .thenReturn(List.of(legacy, current));

        Map<String, Long> result = new AssetRepositoryImpl(mapper)
                .findActiveIndexGenerations(List.of("asset-1", "asset-2"));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                "asset-1", 0L,
                "asset-2", 7L));
    }

    @Test
    void findActiveIndexGenerations_shouldAvoidEmptyInClause() {
        assertThat(new AssetRepositoryImpl(mapper)
                .findActiveIndexGenerations(List.of())).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void activateIndexGeneration_shouldFencePreviousGeneration() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.activateIndexGeneration(
                "kb-1", "asset-1", 2L, 3L,
                "SUCCESS", "SUCCESS", 4, 4, "user-a", now))
                .thenReturn(1);

        boolean activated = new AssetRepositoryImpl(mapper)
                .activateIndexGeneration(
                        "kb-1", "asset-1", 2L, 3L,
                        "SUCCESS", "SUCCESS", 4, 4, "user-a", now);

        assertThat(activated).isTrue();
        verify(mapper).activateIndexGeneration(
                "kb-1", "asset-1", 2L, 3L,
                "SUCCESS", "SUCCESS", 4, 4, "user-a", now);
    }

    @Test
    void listActive_shouldPassAvailabilityCodeToMapper() {
        when(mapper.listActive(
                "kb-1", "guide", "PDF", "FAILED", 24, 48))
                .thenReturn(List.of());

        assertThat(new AssetRepositoryImpl(mapper).listActive(
                "kb-1", "guide", "PDF",
                DocumentAvailabilityStatus.FAILED, 24, 48)).isEmpty();

        verify(mapper).listActive(
                "kb-1", "guide", "PDF", "FAILED", 24, 48);
    }
}
