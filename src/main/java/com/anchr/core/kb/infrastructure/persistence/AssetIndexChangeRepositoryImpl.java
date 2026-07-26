package com.anchr.core.kb.infrastructure.persistence;

import com.anchr.core.kb.domain.model.AssetIndexChange;
import com.anchr.core.kb.domain.model.AssetIndexChangeOperation;
import com.anchr.core.kb.domain.repository.AssetIndexChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * MyBatis implementation of the append-only asset index change log.
 */
@Repository
@RequiredArgsConstructor
public class AssetIndexChangeRepositoryImpl implements AssetIndexChangeRepository {

    private static final int ID_MAX_LENGTH = 64;

    private final AssetIndexChangeMapper mapper;

    @Override
    public void save(AssetIndexChange change) {
        validateNewChange(change);
        if (mapper.insert(toRecord(change)) != 1) {
            throw new IllegalStateException("Asset index change was not persisted.");
        }
    }

    @Override
    public List<AssetIndexChange> listAfterRevision(long exclusiveRevision, int limit) {
        if (exclusiveRevision < 0) {
            throw new IllegalArgumentException("exclusiveRevision must be nonnegative.");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive.");
        }
        List<AssetIndexChangeRecord> records =
                mapper.listAfterRevision(exclusiveRevision, limit);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream().map(this::toDomain).toList();
    }

    @Override
    public long currentRevision() {
        return mapper.currentRevision();
    }

    private void validateNewChange(AssetIndexChange change) {
        if (change == null) {
            throw new IllegalArgumentException("change must not be null.");
        }
        if (change.getRevision() != null) {
            throw new IllegalArgumentException("revision must be absent for a new change.");
        }
        requireText(change.getEventId(), "eventId");
        requireText(change.getKbId(), "kbId");
        requireText(change.getAssetId(), "assetId");
        requireText(change.getCreatedBy(), "createdBy");
        if (change.getOperation() == null) {
            throw new IllegalArgumentException("operation must not be null.");
        }
        if (change.getIndexGeneration() < 0) {
            throw new IllegalArgumentException("indexGeneration must be nonnegative.");
        }
        if (change.getOccurredAt() == null) {
            throw new IllegalArgumentException("occurredAt must not be null.");
        }
    }

    private void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        if (value.length() > ID_MAX_LENGTH) {
            throw new IllegalArgumentException(name + " length must be <= " + ID_MAX_LENGTH + ".");
        }
    }

    private AssetIndexChangeRecord toRecord(AssetIndexChange change) {
        AssetIndexChangeRecord record = new AssetIndexChangeRecord();
        record.setEventId(change.getEventId().trim());
        record.setKbId(change.getKbId().trim());
        record.setAssetId(change.getAssetId().trim());
        record.setOperation(change.getOperation().name());
        record.setIndexGeneration(change.getIndexGeneration());
        record.setOccurredAt(change.getOccurredAt());
        record.setCreatedBy(change.getCreatedBy().trim());
        return record;
    }

    private AssetIndexChange toDomain(AssetIndexChangeRecord record) {
        if (record == null || record.getRevision() == null) {
            throw new IllegalStateException("Persisted asset index change has no revision.");
        }
        if (record.getIndexGeneration() == null || record.getIndexGeneration() < 0) {
            throw new IllegalStateException(
                    "Persisted asset index change has an invalid index generation.");
        }
        if (record.getOccurredAt() == null) {
            throw new IllegalStateException(
                    "Persisted asset index change has no occurrence time.");
        }
        return AssetIndexChange.builder()
                .revision(record.getRevision())
                .eventId(record.getEventId())
                .kbId(record.getKbId())
                .assetId(record.getAssetId())
                .operation(parseOperation(record.getOperation()))
                .indexGeneration(record.getIndexGeneration())
                .occurredAt(record.getOccurredAt())
                .createdBy(record.getCreatedBy())
                .build();
    }

    private AssetIndexChangeOperation parseOperation(String operation) {
        try {
            return AssetIndexChangeOperation.valueOf(operation);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException(
                    "Persisted asset index change has an invalid operation.", exception);
        }
    }
}
