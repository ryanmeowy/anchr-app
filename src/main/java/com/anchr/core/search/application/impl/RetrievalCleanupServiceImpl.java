package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.api.RetrievalCleanupApi;
import com.anchr.core.search.application.api.model.RetrievalAssetCleanupCommand;
import com.anchr.core.search.application.api.model.RetrievalGenerationCleanupCommand;
import com.anchr.core.search.domain.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RetrievalCleanupServiceImpl implements RetrievalCleanupApi {

    private final SegmentRepository segmentRepository;

    @Override
    public void deleteAsset(RetrievalAssetCleanupCommand command) {
        if (command == null
                || !StringUtils.hasText(command.kbId())
                || !StringUtils.hasText(command.assetId())) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST, "kbId and assetId are required.");
        }
        segmentRepository.deleteByAssetId(command.assetId().trim());
    }

    @Override
    public void deleteGeneration(RetrievalGenerationCleanupCommand command) {
        if (command == null
                || !StringUtils.hasText(command.kbId())
                || !StringUtils.hasText(command.assetId())
                || command.generation() < 0L) {
            throw new BusinessException(
                    ApiError.INVALID_REQUEST,
                    "kbId, assetId and a non-negative generation are required.");
        }
        segmentRepository.deleteByAssetGeneration(
                command.assetId().trim(), command.generation());
    }
}
