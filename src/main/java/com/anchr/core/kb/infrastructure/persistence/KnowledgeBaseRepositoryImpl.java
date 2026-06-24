package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStats;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis implementation of knowledge base repository.
 */
@Repository
@RequiredArgsConstructor
public class KnowledgeBaseRepositoryImpl implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper mapper;

    @Override
    public void save(KnowledgeBase knowledgeBase) {
        mapper.insert(toRecord(knowledgeBase));
    }

    @Override
    public Optional<KnowledgeBase> findById(String id) {
        return mapper.findById( id).map(this::toDomain);
    }

    @Override
    public Optional<KnowledgeBase> findActiveById(String id) {
        return mapper.findActiveById( id).map(this::toDomain);
    }

    @Override
    public List<KnowledgeBase> listActiveByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.listActiveByIds( ids).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<KnowledgeBase> searchKbs(String q, String status,
                                         LocalDateTime updatedAfter, LocalDateTime updatedBefore,
                                         int limit, int offset) {
        return mapper.searchKbs(q, status, updatedAfter, updatedBefore, limit, offset).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countKbs(String q, String status,
                         LocalDateTime updatedAfter, LocalDateTime updatedBefore) {
        return mapper.countKbs(q, status, updatedAfter, updatedBefore);
    }

    @Override
    public boolean updateProfile(String id, String name, String description,
                                 String updatedBy, LocalDateTime updatedAt) {
        return mapper.updateProfile( id, name, description, updatedBy, updatedAt) > 0;
    }

    @Override
    public boolean archive(String id, String updatedBy, LocalDateTime updatedAt) {
        return mapper.archive( id, updatedBy, updatedAt) > 0;
    }

    @Override
    public void refreshDocumentStats(String id, String updatedBy, boolean freshIngest) {
        LocalDateTime now = LocalDateTime.now();
        // When freshIngest is true (a document was just ingested), stamp last_ingested_at
        // alongside updated_at; otherwise leave last_ingested_at untouched.
        mapper.refreshDocumentStats( id, updatedBy, now, freshIngest ? now : null);
    }

    @Override
    public List<KnowledgeBaseStats> findStats(List<String> kbIds) {
        return mapper.findStats(kbIds).stream()
                .map(this::toStats)
                .toList();
    }

    private KnowledgeBaseRecord toRecord(KnowledgeBase knowledgeBase) {
        KnowledgeBaseRecord record = new KnowledgeBaseRecord();
        record.setId(knowledgeBase.getId());
        record.setName(knowledgeBase.getName());
        record.setDescription(knowledgeBase.getDescription());
        record.setStatus(knowledgeBase.getStatus().name());
        record.setDocumentCount(knowledgeBase.getDocumentCount());
        record.setSegmentCount(knowledgeBase.getSegmentCount());
        record.setLastIngestedAt(knowledgeBase.getLastIngestedAt());
        record.setCreatedBy(knowledgeBase.getCreatedBy());
        record.setUpdatedBy(knowledgeBase.getUpdatedBy());
        record.setCreatedAt(knowledgeBase.getCreatedAt());
        record.setUpdatedAt(knowledgeBase.getUpdatedAt());
        record.setDeletedAt(knowledgeBase.getDeletedAt());
        return record;
    }

    private KnowledgeBase toDomain(KnowledgeBaseRecord record) {
        return KnowledgeBase.builder()
                .id(record.getId())
                .name(record.getName())
                .description(record.getDescription())
                .status(KnowledgeBaseStatus.valueOf(record.getStatus()))
                .documentCount(defaultInt(record.getDocumentCount()))
                .segmentCount(defaultInt(record.getSegmentCount()))
                .lastIngestedAt(record.getLastIngestedAt())
                .createdBy(record.getCreatedBy())
                .updatedBy(record.getUpdatedBy())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .deletedAt(record.getDeletedAt())
                .build();
    }

    private KnowledgeBaseStats toStats(KnowledgeBaseStatsRecord record) {
        return KnowledgeBaseStats.builder()
                .kbId(record.getKbId())
                .documentCount(defaultInt(record.getDocumentCount()))
                .segmentCount(defaultInt(record.getSegmentCount()))
                .lastIngestedAt(record.getLastIngestedAt())
                .lastIngestionStatus(record.getLastIngestionStatus())
                .lastIngestionTotalCount(defaultInt(record.getLastIngestionTotalCount()))
                .lastIngestionSuccessCount(defaultInt(record.getLastIngestionSuccessCount()))
                .lastIngestionFailureCount(defaultInt(record.getLastIngestionFailureCount()))
                .lastIngestionRunningCount(defaultInt(record.getLastIngestionRunningCount()))
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
