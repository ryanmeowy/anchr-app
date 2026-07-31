package com.anchr.core.settings.application.impl;

import com.anchr.core.settings.application.support.RuntimeConfigCatalog;
import com.anchr.core.settings.application.support.RuntimeConfigResolver;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigQueryServiceImplTest {

    private final RuntimeConfigResolver resolver = mock(RuntimeConfigResolver.class);
    private final RuntimeConfigQueryServiceImpl service =
            new RuntimeConfigQueryServiceImpl(resolver, new RuntimeConfigCatalog());

    @Test
    void shouldNormalizeTypeValidateKeyAndReturnStoredValue() {
        when(resolver.findStoredValue(RuntimeConfigType.AGENT, "maxSteps"))
                .thenReturn(Optional.of("20"));

        assertThat(service.findValue(" agent ", " maxSteps "))
                .contains("20");
        verify(resolver).findStoredValue(RuntimeConfigType.AGENT, "maxSteps");
    }

    @Test
    void shouldReturnEmptyOnlyForAMissingSupportedValue() {
        when(resolver.findStoredValue(RuntimeConfigType.OUTBOX, "batchSize"))
                .thenReturn(Optional.empty());

        assertThat(service.findValue("OUTBOX", "batchSize")).isEmpty();
    }

    @Test
    void shouldRejectUnknownTypeAndKeyBeforeReadingStorage() {
        assertThatThrownBy(() -> service.findValue("UNKNOWN", "batchSize"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported runtime config type");
        assertThatThrownBy(() -> service.findValue("OUTBOX", "unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported runtime config key");
    }
}
