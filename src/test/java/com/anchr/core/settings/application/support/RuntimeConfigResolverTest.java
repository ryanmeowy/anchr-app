package com.anchr.core.settings.application.support;

import com.anchr.core.settings.domain.model.AgentRuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigEntry;
import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.settings.domain.model.SearchRuntimeConfigKey;
import com.anchr.core.settings.domain.repository.RuntimeConfigRepository;
import com.anchr.core.settings.infrastructure.cache.RuntimeConfigCache;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeConfigResolverTest {

    private final RuntimeConfigRepository repository =
            mock(RuntimeConfigRepository.class);
    private final RuntimeConfigCache cache = mock(RuntimeConfigCache.class);
    private final RuntimeConfigCatalog catalog = new RuntimeConfigCatalog();
    private final RuntimeConfigResolver resolver =
            new RuntimeConfigResolver(repository, catalog, cache);

    @Test
    void shouldUseCachedStoredValueWithoutReadingMysql() {
        when(cache.find(RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS))
                .thenReturn(new RuntimeConfigCache.LookupResult(
                        true, Optional.of("20")));

        assertThat(resolver.findStoredValue(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS)).contains("20");
        verify(repository, never()).findByType(RuntimeConfigType.AGENT);
    }

    @Test
    void shouldNegativeCacheMissingSupportedValue() {
        when(cache.find(RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS))
                .thenReturn(new RuntimeConfigCache.LookupResult(
                        true, Optional.empty()));

        assertThat(resolver.findStoredValue(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS)).isEmpty();
        verify(repository, never()).findByType(RuntimeConfigType.AGENT);
    }

    @Test
    void shouldLoadAndPopulateStoredOverridesWhenCacheIsUnavailable() {
        when(cache.find(RuntimeConfigType.SEARCH, SearchRuntimeConfigKey.RANK_CONSTANT))
                .thenReturn(new RuntimeConfigCache.LookupResult(
                        false, Optional.empty()));
        when(repository.findByType(RuntimeConfigType.SEARCH))
                .thenReturn(List.of(entry(
                        RuntimeConfigType.SEARCH,
                        SearchRuntimeConfigKey.RANK_CONSTANT,
                        "80")));

        assertThat(resolver.findStoredValue(
                RuntimeConfigType.SEARCH,
                SearchRuntimeConfigKey.RANK_CONSTANT)).contains("80");
        verify(cache).populate(
                RuntimeConfigType.SEARCH,
                Map.of(SearchRuntimeConfigKey.RANK_CONSTANT, "80"));
    }

    @Test
    void shouldFailWhenPersistedValueIsInvalid() {
        when(cache.find(RuntimeConfigType.AGENT, AgentRuntimeConfigKey.ENABLED))
                .thenReturn(new RuntimeConfigCache.LookupResult(
                        false, Optional.empty()));
        when(repository.findByType(RuntimeConfigType.AGENT))
                .thenReturn(List.of(entry(
                        RuntimeConfigType.AGENT, AgentRuntimeConfigKey.ENABLED, "yes")));

        assertThatThrownBy(() ->
                resolver.findStoredValue(
                        RuntimeConfigType.AGENT, AgentRuntimeConfigKey.ENABLED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
    }

    @Test
    void shouldValidateCachedStoredValuesBeforeReturningThem() {
        when(cache.find(
                RuntimeConfigType.SEARCH, SearchRuntimeConfigKey.TEXT_SIMILARITY))
                .thenReturn(new RuntimeConfigCache.LookupResult(
                        true, Optional.of("2.5")));

        assertThatThrownBy(() -> resolver.findStoredValue(
                RuntimeConfigType.SEARCH, SearchRuntimeConfigKey.TEXT_SIMILARITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
    }

    private RuntimeConfigEntry entry(
            RuntimeConfigType type, RuntimeConfigKey key, String value) {
        return new RuntimeConfigEntry(
                type, key, value, "admin", LocalDateTime.now());
    }
}
