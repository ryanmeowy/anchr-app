package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.DocumentAsset;
import com.anchr.core.kb.domain.model.DocumentIndexStatus;
import com.anchr.core.kb.domain.model.DocumentParseStatus;
import com.anchr.core.kb.domain.repository.DocumentAssetRepository;
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
public class MyBatisDocumentAssetRepository implements DocumentAssetRepository {

    private final DocumentAssetMapper mapper;

    @Override
    public void save(DocumentAsset documentAsset) {
        mapper.insert(toRecord(documentAsset));
    }

    @Override
    public Optional<DocumentAsset> findActiveById(String kbId, String assetId) {
        return mapper.findActiveById(kbId, assetId).map(this::toDomain);
    }

    @Override
    public List<DocumentAsset> listActive(String kbId, int limit, int offset) {
        return mapper.listActive(kbId, limit, offset).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countActive(String kbId) {
        return mapper.countActive(kbId);
    }

    @Override
    public Optional<DocumentAsset> findActiveByHash(String kbId, String fileHash) {
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

    private DocumentAsset toDomain(DocumentAssetRecord record) {
        return DocumentAsset.builder()
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

    private DocumentAssetRecord toRecord(DocumentAsset documentAsset) {
        DocumentAssetRecord record = new DocumentAssetRecord();
        record.setId(documentAsset.getId());
        record.setKbId(documentAsset.getKbId());
        record.setFileName(documentAsset.getFileName());
        record.setTitle(documentAsset.getTitle());
        record.setFileType(documentAsset.getFileType());
        record.setMimeType(documentAsset.getMimeType());
        record.setSizeBytes(documentAsset.getSizeBytes());
        record.setFileHash(documentAsset.getFileHash());
        record.setObjectKey(documentAsset.getObjectKey());
        record.setPreviewObjectKey(documentAsset.getPreviewObjectKey());
        record.setThumbnailKey(documentAsset.getThumbnailKey());
        record.setSourceUrl(documentAsset.getSourceUrl());
        record.setParseStatus(documentAsset.getParseStatus().name());
        record.setIndexStatus(documentAsset.getIndexStatus().name());
        record.setSegmentCount(documentAsset.getSegmentCount());
        record.setEmbeddingProfile(documentAsset.getEmbeddingProfile());
        record.setErrorCode(documentAsset.getErrorCode());
        record.setErrorMessage(documentAsset.getErrorMessage());
        record.setCreatedBy(documentAsset.getCreatedBy());
        record.setUpdatedBy(documentAsset.getUpdatedBy());
        record.setCreatedAt(documentAsset.getCreatedAt());
        record.setUpdatedAt(documentAsset.getUpdatedAt());
        record.setDeletedAt(documentAsset.getDeletedAt());
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
