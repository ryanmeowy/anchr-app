package com.anchr.core.settings.application.support;

import com.anchr.core.settings.domain.model.AgentRuntimeConfigKey;
import com.anchr.core.settings.domain.model.ConversationRuntimeConfigKey;
import com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey;
import com.anchr.core.settings.domain.model.OutboxRuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigEntry;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.settings.domain.model.SearchRuntimeConfigKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigCatalogTest {

    private final RuntimeConfigCatalog catalog =
            new RuntimeConfigCatalog();

    @Test
    void shouldPreserveCurrentBootstrapDefaults() {
        assertThat(catalog.defaults(RuntimeConfigType.SEARCH))
                .containsEntry(SearchRuntimeConfigKey.RANK_CONSTANT, "60")
                .containsEntry(SearchRuntimeConfigKey.FUSION_ALPHA, "0.6");
        assertThat(catalog.defaults(RuntimeConfigType.CONVERSATION))
                .containsEntry(ConversationRuntimeConfigKey.INTENT_ROUTING_ENABLED, "false")
                .containsEntry(ConversationRuntimeConfigKey.INTENT_TIMEOUT_SECONDS, "5");
        assertThat(catalog.defaults(RuntimeConfigType.AGENT))
                .containsEntry(AgentRuntimeConfigKey.MAX_STEPS, "12")
                .containsEntry(AgentRuntimeConfigKey.SUMMARY_MAX_DOCUMENTS, "3");
        assertThat(catalog.defaults(RuntimeConfigType.INGESTION))
                .containsEntry(IngestionRuntimeConfigKey.CLAIM_BATCH_SIZE, "32")
                .containsEntry(IngestionRuntimeConfigKey.CHUNK_MIN_TOKENS, "200")
                .containsEntry(IngestionRuntimeConfigKey.CHUNK_MAX_TOKENS, "1200")
                .containsEntry(IngestionRuntimeConfigKey.DOCLING_MAX_RESPONSE_MIB, "256");
        assertThat(catalog.defaults(RuntimeConfigType.OUTBOX))
                .containsEntry(OutboxRuntimeConfigKey.RETENTION_DAYS, "90");
    }

    @Test
    void shouldRejectUnknownAndInvalidValues() {
        assertThatThrownBy(() -> RuntimeConfigKey.parse(
                RuntimeConfigType.AGENT, "unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported runtime config key");
        assertThatThrownBy(() -> catalog.normalize(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.ENABLED, "yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
        assertThatThrownBy(() -> catalog.normalize(
                RuntimeConfigType.SEARCH, SearchRuntimeConfigKey.FUSION_ALPHA, "1.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
    }

    @Test
    void shouldKeepTheKeyAssociatedWithItsTypeAtTheStringBoundary() {
        assertThat(RuntimeConfigKey.parse(RuntimeConfigType.SEARCH, "textTopK"))
                .isSameAs(SearchRuntimeConfigKey.TEXT_TOP_K);
        assertThat(SearchRuntimeConfigKey.TEXT_TOP_K.type())
                .isEqualTo(RuntimeConfigType.SEARCH);
        assertThatThrownBy(() -> RuntimeConfigKey.parse(
                RuntimeConfigType.AGENT, "textTopK"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported runtime config key for AGENT");
        assertThatThrownBy(() -> new RuntimeConfigEntry(
                RuntimeConfigType.AGENT,
                SearchRuntimeConfigKey.TEXT_TOP_K,
                "10",
                "admin",
                LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to AGENT");
    }

    @Test
    void shouldValidateCrossFieldRulesAfterPartialMerge() {
        LinkedHashMap<RuntimeConfigKey, String> search =
                new LinkedHashMap<>(catalog.defaults(RuntimeConfigType.SEARCH));
        search.put(SearchRuntimeConfigKey.WINDOW_MIN, "81");

        assertThatThrownBy(() ->
                catalog.validateResolved(RuntimeConfigType.SEARCH, search))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("windowMin must not exceed windowMax");

        LinkedHashMap<RuntimeConfigKey, String> agent =
                new LinkedHashMap<>(catalog.defaults(RuntimeConfigType.AGENT));
        agent.put(AgentRuntimeConfigKey.SUMMARY_BATCH_CHARS, "500001");

        assertThatThrownBy(() ->
                catalog.validateResolved(RuntimeConfigType.AGENT, agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("summaryBatchChars must not exceed summaryMaxChars");

        LinkedHashMap<RuntimeConfigKey, String> ingestion =
                new LinkedHashMap<>(catalog.defaults(RuntimeConfigType.INGESTION));
        ingestion.put(IngestionRuntimeConfigKey.CHUNK_MIN_TOKENS, "1201");

        assertThatThrownBy(() ->
                catalog.validateResolved(RuntimeConfigType.INGESTION, ingestion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chunkMinTokens must not exceed chunkMaxTokens");
    }
}
