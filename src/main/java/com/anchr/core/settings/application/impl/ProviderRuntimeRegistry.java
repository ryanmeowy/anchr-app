package com.anchr.core.settings.application.impl;

import com.anchr.core.settings.application.provider.ProviderIdentity;
import com.anchr.core.settings.domain.model.ProviderType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of provider beans available in the running process.
 */
@Component
public class ProviderRuntimeRegistry {

    private final Map<String, ProviderIdentity> providers;

    public ProviderRuntimeRegistry(List<ProviderIdentity> providerList) {
        Map<String, ProviderIdentity> indexed = new LinkedHashMap<>();
        for (ProviderIdentity provider : providerList) {
            indexed.put(key(provider.providerType(), provider.providerName()), provider);
        }
        this.providers = Map.copyOf(indexed);
    }

    public List<ProviderIdentity> list() {
        return providers.values().stream().toList();
    }

    public boolean available(ProviderType providerType, String providerName) {
        return find(providerType, providerName).isPresent();
    }

    public Optional<ProviderIdentity> find(ProviderType providerType, String providerName) {
        return Optional.ofNullable(providers.get(key(providerType, providerName)));
    }

    private String key(ProviderType providerType, String providerName) {
        return providerType.name() + ":" + normalize(providerName);
    }

    private String normalize(String providerName) {
        return providerName == null ? "" : providerName.trim().toLowerCase(Locale.ROOT);
    }
}
