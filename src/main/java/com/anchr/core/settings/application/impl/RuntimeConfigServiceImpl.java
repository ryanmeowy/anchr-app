package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.settings.application.RuntimeConfigService;
import com.anchr.core.settings.application.support.RuntimeConfigCatalog;
import com.anchr.core.settings.application.support.RuntimeConfigResolver;
import com.anchr.core.settings.domain.model.RuntimeConfigEntry;
import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.settings.domain.repository.RuntimeConfigRepository;
import com.anchr.core.settings.infrastructure.cache.RuntimeConfigCache;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigGroupDTO;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigParamDTO;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigResponseDTO;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigUpdateRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuntimeConfigServiceImpl implements RuntimeConfigService {

    private final RuntimeConfigRepository repository;
    private final RuntimeConfigCatalog catalog;
    private final RuntimeConfigResolver resolver;
    private final RuntimeConfigCache cache;
    private final TransactionTemplate transactionTemplate;

    @Override
    public RuntimeConfigResponseDTO getAll() {
        List<RuntimeConfigGroupDTO> groups = new ArrayList<>();
        for (RuntimeConfigType type : RuntimeConfigType.values()) {
            groups.add(toGroup(type, resolver.resolve(type)));
        }
        return new RuntimeConfigResponseDTO(groups);
    }

    @Override
    public RuntimeConfigGroupDTO update(RuntimeConfigUpdateRequestDTO request) {
        RuntimeConfigType type = RuntimeConfigType.parse(request.type());
        LinkedHashMap<RuntimeConfigKey, String> normalized =
                normalize(type, request.params());
        Map<RuntimeConfigKey, String> proposed =
                new LinkedHashMap<>(resolver.loadFromDatabase(type));
        proposed.putAll(normalized);
        catalog.validateResolved(type, proposed);

        LocalDateTime updatedAt = LocalDateTime.now();
        String updatedBy = UserContextHolder.get().userId();
        List<RuntimeConfigEntry> entries = normalized.entrySet().stream()
                .map(entry -> new RuntimeConfigEntry(
                        type, entry.getKey(), entry.getValue(), updatedBy, updatedAt))
                .toList();
        transactionTemplate.executeWithoutResult(status -> repository.upsertAll(entries));

        Map<RuntimeConfigKey, String> stored = resolver.loadStoredFromDatabase(type);
        cache.replaceAfterDatabaseCommit(type, stored, normalized.keySet());
        LinkedHashMap<RuntimeConfigKey, String> effective =
                new LinkedHashMap<>(catalog.defaults(type));
        effective.putAll(stored);
        catalog.validateResolved(type, effective);
        return toGroup(type, effective);
    }

    private LinkedHashMap<RuntimeConfigKey, String> normalize(
            RuntimeConfigType type, List<RuntimeConfigParamDTO> params) {
        if (params == null || params.isEmpty()) {
            throw new IllegalArgumentException("runtime config params cannot be empty");
        }
        Set<RuntimeConfigKey> keys = new HashSet<>();
        LinkedHashMap<RuntimeConfigKey, String> values = new LinkedHashMap<>();
        for (RuntimeConfigParamDTO param : params) {
            if (param == null || param.key() == null || param.key().isBlank()) {
                throw new IllegalArgumentException("runtime config key is required");
            }
            RuntimeConfigKey key = RuntimeConfigKey.parse(type, param.key());
            if (!keys.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate runtime config key: " + key.propertyName());
            }
            values.put(key, catalog.normalize(type, key, param.value()));
        }
        return values;
    }

    private RuntimeConfigGroupDTO toGroup(
            RuntimeConfigType type, Map<RuntimeConfigKey, String> values) {
        List<RuntimeConfigParamDTO> params = catalog.keys(type).stream()
                .map(key -> new RuntimeConfigParamDTO(
                        key.propertyName(), values.get(key)))
                .toList();
        return new RuntimeConfigGroupDTO(type.name(), params);
    }
}
