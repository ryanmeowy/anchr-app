package com.anchr.core.settings.application.impl;

import com.anchr.core.settings.application.api.RuntimeConfigQueryApi;
import com.anchr.core.settings.application.support.RuntimeConfigResolver;
import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RuntimeConfigQueryServiceImpl implements RuntimeConfigQueryApi {

    private final RuntimeConfigResolver resolver;

    @Override
    public Optional<String> findValue(RuntimeConfigType type, RuntimeConfigKey key) {
        key.requireType(type);
        return resolver.findStoredValue(type, key);
    }
}
