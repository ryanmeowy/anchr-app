package com.anchr.core.settings.application.impl;

import com.anchr.core.settings.application.api.RuntimeConfigQueryApi;
import com.anchr.core.settings.application.support.RuntimeConfigCatalog;
import com.anchr.core.settings.application.support.RuntimeConfigResolver;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RuntimeConfigQueryServiceImpl implements RuntimeConfigQueryApi {

    private final RuntimeConfigResolver resolver;
    private final RuntimeConfigCatalog catalog;

    @Override
    public Optional<String> findValue(String type, String key) {
        RuntimeConfigType resolvedType = RuntimeConfigType.parse(type);
        String resolvedKey = key == null ? "" : key.trim();
        catalog.requireSupported(resolvedType, resolvedKey);
        return resolver.findStoredValue(resolvedType, resolvedKey);
    }
}
