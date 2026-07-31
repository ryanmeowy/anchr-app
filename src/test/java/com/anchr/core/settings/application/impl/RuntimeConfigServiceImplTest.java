package com.anchr.core.settings.application.impl;

import com.anchr.core.common.application.context.RequestUserContext;
import com.anchr.core.common.application.context.UserContextHolder;
import com.anchr.core.settings.application.support.RuntimeConfigCatalog;
import com.anchr.core.settings.application.support.RuntimeConfigResolver;
import com.anchr.core.settings.domain.model.RuntimeConfigEntry;
import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.AgentRuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.settings.domain.repository.RuntimeConfigRepository;
import com.anchr.core.settings.infrastructure.cache.RuntimeConfigCache;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigParamDTO;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigUpdateRequestDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceImplTest {

    private final RuntimeConfigRepository repository =
            mock(RuntimeConfigRepository.class);
    private final RuntimeConfigResolver resolver = mock(RuntimeConfigResolver.class);
    private final RuntimeConfigCache cache = mock(RuntimeConfigCache.class);
    private final TransactionTemplate transactionTemplate =
            mock(TransactionTemplate.class);
    private final RuntimeConfigCatalog catalog =
            new RuntimeConfigCatalog();
    private final RuntimeConfigServiceImpl service = new RuntimeConfigServiceImpl(
            repository, catalog, resolver, cache, transactionTemplate);

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new RequestUserContext("admin-1", "ADMIN"));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void shouldBatchUpsertAndRefreshRedisWithCompleteType() {
        Map<RuntimeConfigKey, String> current =
                new LinkedHashMap<>(catalog.defaults(RuntimeConfigType.AGENT));
        Map<RuntimeConfigKey, String> stored = Map.of(
                AgentRuntimeConfigKey.ENABLED, "false",
                AgentRuntimeConfigKey.MAX_STEPS, "20");
        when(resolver.loadFromDatabase(RuntimeConfigType.AGENT))
                .thenReturn(current);
        when(resolver.loadStoredFromDatabase(RuntimeConfigType.AGENT))
                .thenReturn(stored);

        var updated = service.update(new RuntimeConfigUpdateRequestDTO(
                "agent",
                List.of(
                        new RuntimeConfigParamDTO("enabled", "false"),
                        new RuntimeConfigParamDTO("maxSteps", "20"))));

        ArgumentCaptor<List<RuntimeConfigEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(entries.capture());
        assertThat(entries.getValue()).extracting(RuntimeConfigEntry::key)
                .containsExactly(
                        AgentRuntimeConfigKey.ENABLED,
                        AgentRuntimeConfigKey.MAX_STEPS);
        assertThat(entries.getValue()).allMatch(
                entry -> "admin-1".equals(entry.updatedBy()));
        verify(cache).replaceAfterDatabaseCommit(
                RuntimeConfigType.AGENT,
                stored,
                java.util.Set.of(
                        AgentRuntimeConfigKey.ENABLED,
                        AgentRuntimeConfigKey.MAX_STEPS));
        assertThat(updated.type()).isEqualTo("AGENT");
        assertThat(updated.params()).anySatisfy(param -> {
            assertThat(param.key()).isEqualTo("enabled");
            assertThat(param.value()).isEqualTo("false");
        });
    }

    @Test
    void shouldRejectDuplicateKeysBeforeDatabaseWrite() {
        assertThatThrownBy(() -> service.update(new RuntimeConfigUpdateRequestDTO(
                "AGENT",
                List.of(
                        new RuntimeConfigParamDTO("enabled", "true"),
                        new RuntimeConfigParamDTO("enabled", "false")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate runtime config key: enabled");

        verify(repository, never()).upsertAll(any());
        verify(cache, never()).replaceAfterDatabaseCommit(any(), any(), any());
    }

    @Test
    void shouldRejectInvalidValueBeforeDatabaseWrite() {
        assertThatThrownBy(() -> service.update(new RuntimeConfigUpdateRequestDTO(
                "SEARCH",
                List.of(new RuntimeConfigParamDTO("fusionAlpha", "2")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");

        verify(repository, never()).upsertAll(any());
    }

    @Test
    void shouldRejectUnknownTypeAndKeyBeforeOpeningTransaction() {
        assertThatThrownBy(() -> service.update(new RuntimeConfigUpdateRequestDTO(
                "UNKNOWN",
                List.of(new RuntimeConfigParamDTO("enabled", "true")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported runtime config type");

        assertThatThrownBy(() -> service.update(new RuntimeConfigUpdateRequestDTO(
                "OUTBOX",
                List.of(new RuntimeConfigParamDTO("unknown", "1")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported runtime config key");

        verify(transactionTemplate, never()).executeWithoutResult(any());
        verify(repository, never()).upsertAll(any());
        verify(cache, never()).replaceAfterDatabaseCommit(any(), any(), any());
    }
}
