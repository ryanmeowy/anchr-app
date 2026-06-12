package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis implementation of document asset repository.
 */
@Repository
@RequiredArgsConstructor
public class AssetRepositoryImpl implements AssetRepository {

    private final AssetMapper mapper;

    @Override
    public void save(Asset asset) {
        mapper.insert(toRecord(asset));
    }

    @Override
    public Optional<Asset> findActiveById(String kbId, String assetId) {
        return mapper.findActiveById(kbId, assetId).map(this::toDomain);
    }

    @Override
    public List<Asset> listActive(String kbId, int limit, int offset) {
        return mapper.listActive(kbId, limit, offset).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countActive(String kbId) {
        return mapper.countActive(kbId);
    }

    @Override
    public Optional<Asset> findActiveByHash(String kbId, String fileHash) {
        return mapper.findActiveByHash(kbId, fileHash).map(this::toDomain);
    }

    @Override
    public boolean updateStatuses(String kbId, String assetId,
                                  String parseStatus, String indexStatus,
                                  String updatedBy, LocalDateTime updatedAt) {
        return mapper.updateStatuses(kbId, assetId, parseStatus, indexStatus, updatedBy, updatedAt) > 0;
    }

    @Override
    public boolean updateIngestionResult(String kbId, String assetId,
                                         String parseStatus, String indexStatus, int segmentCount,
                                         String errorCode, String errorMessage,
                                         String updatedBy, LocalDateTime updatedAt) {
        return mapper.updateIngestionResult(kbId, assetId, parseStatus, indexStatus, segmentCount,
                errorCode, errorMessage, updatedBy, updatedAt) > 0;
    }

    @Override
    public boolean markDeleted(String kbId, String assetId,
                               String updatedBy, LocalDateTime updatedAt) {
        return mapper.markDeleted(kbId, assetId, updatedBy, updatedAt) > 0;
    }

    private Asset toDomain(AssetRecord record) {
        return Asset.builder()
                .id(record.getId())
                .kbId(record.getKbId())
                .fileName(record.getFileName())
                .title(record.getTitle())
                .fileType(record.getFileType())
                .mimeType(record.getMimeType())
                .sizeBytes(record.getSizeBytes())
                .fileHash(record.getFileHash())
                .objectKey(record.getObjectKey())
                .previewObjectKey(record.getPreviewObjectKey())
                .thumbnailKey(record.getThumbnailKey())
                .sourceUrl(record.getSourceUrl())
                .parseStatus(parseStatus(record.getParseStatus()))
                .indexStatus(indexStatus(record.getIndexStatus()))
                .segmentCount(defaultInt(record.getSegmentCount()))
                .embeddingProfile(record.getEmbeddingProfile())
                .errorCode(record.getErrorCode())
                .errorMessage(record.getErrorMessage())
                .createdBy(record.getCreatedBy())
                .updatedBy(record.getUpdatedBy())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .deletedAt(record.getDeletedAt())
                .build();
    }

    private AssetRecord toRecord(Asset asset) {
        AssetRecord record = new AssetRecord();
        record.setId(asset.getId());
        record.setKbId(asset.getKbId());
        record.setFileName(asset.getFileName());
        record.setTitle(asset.getTitle());
        record.setFileType(asset.getFileType());
        record.setMimeType(asset.getMimeType());
        record.setSizeBytes(asset.getSizeBytes());
        record.setFileHash(asset.getFileHash());
        record.setObjectKey(asset.getObjectKey());
        record.setPreviewObjectKey(asset.getPreviewObjectKey());
        record.setThumbnailKey(asset.getThumbnailKey());
        record.setSourceUrl(asset.getSourceUrl());
        record.setParseStatus(asset.getParseStatus().name());
        record.setIndexStatus(asset.getIndexStatus().name());
        record.setSegmentCount(asset.getSegmentCount());
        record.setEmbeddingProfile(asset.getEmbeddingProfile());
        record.setErrorCode(asset.getErrorCode());
        record.setErrorMessage(asset.getErrorMessage());
        record.setCreatedBy(asset.getCreatedBy());
        record.setUpdatedBy(asset.getUpdatedBy());
        record.setCreatedAt(asset.getCreatedAt());
        record.setUpdatedAt(asset.getUpdatedAt());
        record.setDeletedAt(asset.getDeletedAt());
        return record;
    }

    private DocumentParseStatus parseStatus(String status) {
        return status == null ? DocumentParseStatus.PENDING : DocumentParseStatus.valueOf(status);
    }

    private DocumentIndexStatus indexStatus(String status) {
        return status == null ? DocumentIndexStatus.PENDING : DocumentIndexStatus.valueOf(status);
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
