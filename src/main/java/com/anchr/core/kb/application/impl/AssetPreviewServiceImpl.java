package com.anchr.core.kb.application.impl;

import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.kb.application.AssetPreviewService;
import com.anchr.core.kb.application.KnowledgeBaseService;
import com.anchr.core.kb.application.support.AssetPreviewAccessCache;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.interfaces.rest.dto.AssetPreviewDTO;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Default whole-document preview service for Library assets.
 */
@Service
@RequiredArgsConstructor
public class AssetPreviewServiceImpl implements AssetPreviewService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final SearchObjectStoragePort objectStoragePort;
    private final AssetPreviewAccessCache previewAccessCache;

    @Override
    public AssetPreviewDTO getPreview(String kbId, String assetId) {
        KnowledgeBase knowledgeBase = knowledgeBaseService.get(kbId);
        Asset asset = knowledgeBaseService.getDocument(kbId, assetId);
        String accessTokenHash = currentAccessTokenHash();
        AssetPreviewAccessCache.AssetPreviewAccess access = previewAccessCache
                .find(asset.getId(), accessTokenHash)
                .orElseGet(() -> buildAndCacheAccess(asset, accessTokenHash));

        return AssetPreviewDTO.builder()
                .assetId(asset.getId())
                .kbId(asset.getKbId())
                .kbName(knowledgeBase.getName())
                .fileName(asset.getFileName())
                .title(asset.getTitle())
                .fileType(asset.getFileType())
                .mimeType(asset.getMimeType())
                .sizeBytes(asset.getSizeBytes())
                .versionNo(asset.getVersionNo())
                .createdAt(asset.getCreatedAt())
                .parseStatus(asset.getParseStatus().name())
                .indexStatus(asset.getIndexStatus().name())
                .segmentCount(asset.getSegmentCount())
                .previewType(asset.getFileType())
                .previewUrl(access.previewUrl())
                .expiresAt(access.expiresAt())
                .build();
    }

    private AssetPreviewAccessCache.AssetPreviewAccess buildAndCacheAccess(
            Asset asset, String accessTokenHash) {
        SignedAccess preview = resolvePreview(asset);
        AssetPreviewAccessCache.AssetPreviewAccess access = new AssetPreviewAccessCache.AssetPreviewAccess(
                preview.url(), preview.expiresAt());
        previewAccessCache.save(asset.getId(), accessTokenHash, access);
        return access;
    }

    private SignedAccess resolvePreview(Asset asset) {
        if (StringUtils.hasText(asset.getPreviewObjectKey())) {
            return sign(asset.getPreviewObjectKey().trim());
        }
        if (StringUtils.hasText(asset.getObjectKey())) {
            return sign(asset.getObjectKey().trim());
        }
        throw new BusinessException(ApiError.DOCUMENT_PREVIEW_NOT_AVAILABLE);
    }

    private SignedAccess sign(String objectKey) {
        try {
            SearchObjectStoragePort.SignedObjectUrl signed = objectStoragePort.buildPreviewUrl(objectKey);
            if (signed == null || !StringUtils.hasText(signed.url())) {
                throw new BusinessException(ApiError.PREVIEW_URL_SIGN_FAILED);
            }
            return new SignedAccess(signed.url(), signed.expiresAt());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ApiError.PREVIEW_URL_SIGN_FAILED, e);
        }
    }

    private String currentAccessTokenHash() {
        String accessTokenHash = UserContextHolder.get().accessTokenHash();
        if (!StringUtils.hasText(accessTokenHash)) {
            throw new BusinessException(ApiError.UNAUTHORIZED, "Authenticated token context is required.");
        }
        return accessTokenHash;
    }

    private record SignedAccess(String url, Long expiresAt) {
    }
}
