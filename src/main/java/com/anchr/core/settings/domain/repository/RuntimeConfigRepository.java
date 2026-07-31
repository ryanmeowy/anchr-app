package com.anchr.core.settings.domain.repository;

import com.anchr.core.settings.domain.model.RuntimeConfigEntry;
import com.anchr.core.settings.domain.model.RuntimeConfigType;

import java.util.List;

public interface RuntimeConfigRepository {

    List<RuntimeConfigEntry> findByType(RuntimeConfigType type);

    void upsertAll(List<RuntimeConfigEntry> entries);
}
