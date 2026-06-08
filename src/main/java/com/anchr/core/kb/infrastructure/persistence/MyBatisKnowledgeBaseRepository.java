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
public class MyBatisKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper mapper;

    @Override
    public void save(KnowledgeBase knowledgeBase) {
        mapper.insert(toRecord(knowledgeBase));
    }

    @Override
    public Optional<KnowledgeBase> findById(String workspaceId, String id) {
        return mapper.findById(workspaceId, id).map(this::toDomain);
    }

    @Override
    public Optional<KnowledgeBase> findActiveById(String workspaceId, String id) {
        return mapper.findActiveById(workspaceId, id).map(this::toDomain);
    }

    @Override
    public List<KnowledgeBase> listActiveByIds(String workspaceId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.listActiveByIds(workspaceId, ids).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<KnowledgeBase> listActive(String workspaceId, int limit, int offset) {
        return mapper.listActive(workspaceId, limit, offset).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countActive(String workspaceId) {
        return mapper.countActive(workspaceId);
    }

    @Override
    public boolean updateProfile(String workspaceId, String id, String name, String description,
                                 String updatedBy, LocalDateTime updatedAt) {
        return mapper.updateProfile(workspaceId, id, name, description, updatedBy, updatedAt) > 0;
    }

    @Override
    public boolean archive(String workspaceId, String id, String updatedBy, LocalDateTime updatedAt) {
        return mapper.archive(workspaceId, id, updatedBy, updatedAt) > 0;
    }

    @Override
    public void refreshDocumentStats(String workspaceId, String id, String updatedBy, LocalDateTime updatedAt) {
        mapper.refreshDocumentStats(workspaceId, id, updatedBy, updatedAt);
    }

    @Override
    public Optional<KnowledgeBaseStats> findStats(String workspaceId, String id) {
        return mapper.findStats(workspaceId, id).map(this::toStats);
    }

    private KnowledgeBaseRecord toRecord(KnowledgeBase knowledgeBase) {
        KnowledgeBaseRecord record = new KnowledgeBaseRecord();
        record.setId(knowledgeBase.getId());
        record.setWorkspaceId(knowledgeBase.getWorkspaceId());
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
                .workspaceId(record.getWorkspaceId())
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
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
