package com.anchr.core.settings.infrastructure.persistence;

import com.anchr.core.settings.domain.model.RuntimeConfigEntry;
import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.settings.domain.repository.RuntimeConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class RuntimeConfigRepositoryImpl implements RuntimeConfigRepository {

    private final RuntimeConfigMapper mapper;

    @Override
    public List<RuntimeConfigEntry> findByType(RuntimeConfigType type) {
        return mapper.findByType(type.name()).stream()
                .map(record -> {
                    RuntimeConfigType entryType =
                            RuntimeConfigType.valueOf(record.getType());
                    return new RuntimeConfigEntry(
                            entryType,
                            RuntimeConfigKey.parse(entryType, record.getParamKey()),
                            record.getParamValue(),
                            record.getUpdatedBy(),
                            record.getUpdatedAt());
                })
                .toList();
    }

    @Override
    public void upsertAll(List<RuntimeConfigEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        mapper.upsertAll(entries.stream().map(this::toRecord).toList());
    }

    private RuntimeConfigRecord toRecord(RuntimeConfigEntry entry) {
        RuntimeConfigRecord record = new RuntimeConfigRecord();
        record.setType(entry.type().name());
        entry.key().requireType(entry.type());
        record.setParamKey(entry.key().propertyName());
        record.setParamValue(entry.value());
        record.setUpdatedBy(entry.updatedBy());
        record.setUpdatedAt(entry.updatedAt());
        return record;
    }
}
