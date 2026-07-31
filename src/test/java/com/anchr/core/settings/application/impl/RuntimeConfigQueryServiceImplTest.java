package com.anchr.core.settings.application.impl;

import com.anchr.core.settings.application.support.RuntimeConfigResolver;
import com.anchr.core.settings.domain.model.AgentRuntimeConfigKey;
import com.anchr.core.settings.domain.model.OutboxRuntimeConfigKey;
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
            new RuntimeConfigQueryServiceImpl(resolver);

    @Test
    void shouldReturnStoredValueForTypedKey() {
        when(resolver.findStoredValue(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS))
                .thenReturn(Optional.of("20"));

        assertThat(service.findValue(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS))
                .contains("20");
        verify(resolver).findStoredValue(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS);
    }

    @Test
    void shouldReturnEmptyOnlyForAMissingSupportedValue() {
        when(resolver.findStoredValue(
                RuntimeConfigType.OUTBOX, OutboxRuntimeConfigKey.BATCH_SIZE))
                .thenReturn(Optional.empty());

        assertThat(service.findValue(
                RuntimeConfigType.OUTBOX, OutboxRuntimeConfigKey.BATCH_SIZE)).isEmpty();
    }

    @Test
    void shouldRejectAKeyFromAnotherTypeBeforeReadingStorage() {
        assertThatThrownBy(() -> service.findValue(
                RuntimeConfigType.OUTBOX, AgentRuntimeConfigKey.MAX_STEPS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to OUTBOX");
    }
}
