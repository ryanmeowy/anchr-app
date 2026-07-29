package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.api.model.RetrievalAssetCleanupCommand;
import com.anchr.core.search.application.api.model.RetrievalGenerationCleanupCommand;
import com.anchr.core.search.domain.repository.SegmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RetrievalCleanupServiceImplTest {

    @Mock private SegmentRepository repository;

    @Test
    void deleteCommands_shouldDelegateAndRemainReplaySafe() {
        RetrievalCleanupServiceImpl service = new RetrievalCleanupServiceImpl(repository);

        service.deleteAsset(new RetrievalAssetCleanupCommand("kb-1", "asset-1"));
        service.deleteAsset(new RetrievalAssetCleanupCommand("kb-1", "asset-1"));
        service.deleteGeneration(
                new RetrievalGenerationCleanupCommand("kb-1", "asset-1", 2L));
        service.deleteGeneration(
                new RetrievalGenerationCleanupCommand("kb-1", "asset-1", 2L));

        verify(repository, times(2)).deleteByAssetId("asset-1");
        verify(repository, times(2)).deleteByAssetGeneration("asset-1", 2L);
    }

    @Test
    void deleteGeneration_shouldRejectInvalidBoundaryCommand() {
        RetrievalCleanupServiceImpl service = new RetrievalCleanupServiceImpl(repository);

        assertThatThrownBy(() -> service.deleteGeneration(
                new RetrievalGenerationCleanupCommand(" ", "asset-1", 2L)))
                .isInstanceOf(BusinessException.class);

        verify(repository, never()).deleteByAssetGeneration(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Long.class));
    }
}
