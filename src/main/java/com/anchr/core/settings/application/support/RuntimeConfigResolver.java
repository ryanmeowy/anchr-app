package com.anchr.core.settings.application.support;

import com.anchr.core.settings.domain.model.RuntimeConfigEntry;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.settings.domain.repository.RuntimeConfigRepository;
import com.anchr.core.settings.infrastructure.cache.RuntimeConfigCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RuntimeConfigResolver {

    private final RuntimeConfigRepository repository;
    private final RuntimeConfigCatalog catalog;
    private final RuntimeConfigCache cache;

    public Map<String, String> resolve(RuntimeConfigType type) {
        LinkedHashMap<String, String> effective =
                new LinkedHashMap<>(catalog.defaults(type));
        effective.putAll(resolveStored(type));
        catalog.validateResolved(type, effective);
        return Map.copyOf(effective);
    }

    public Map<String, String> loadFromDatabase(RuntimeConfigType type) {
        LinkedHashMap<String, String> values =
                new LinkedHashMap<>(catalog.defaults(type));
        values.putAll(loadStoredFromDatabase(type));
        catalog.validateResolved(type, values);
        return Map.copyOf(values);
    }

    public Optional<String> findStoredValue(RuntimeConfigType type, String key) {
        RuntimeConfigCache.LookupResult cached = cache.find(type, key);
        if (cached.cacheReady()) {
            return cached.value()
                    .map(value -> catalog.normalize(type, key, value));
        }
        Map<String, String> stored = loadStoredFromDatabase(type);
        cache.populate(type, stored);
        return Optional.ofNullable(stored.get(key));
    }

    public Map<String, String> loadStoredFromDatabase(RuntimeConfigType type) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (RuntimeConfigEntry entry : repository.findByType(type)) {
            catalog.requireSupported(type, entry.key());
            values.put(
                    entry.key(),
                    catalog.normalize(type, entry.key(), entry.value()));
        }
        return Map.copyOf(values);
    }

    private Map<String, String> resolveStored(RuntimeConfigType type) {
        return cache.getStored(type).orElseGet(() -> {
            Map<String, String> values = loadStoredFromDatabase(type);
            cache.populate(type, values);
            return values;
        });
    }
}
