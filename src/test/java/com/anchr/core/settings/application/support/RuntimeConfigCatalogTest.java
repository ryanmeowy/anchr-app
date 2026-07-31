package com.anchr.core.settings.application.support;

import com.anchr.core.settings.domain.model.RuntimeConfigType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigCatalogTest {

    private final RuntimeConfigCatalog catalog =
            new RuntimeConfigCatalog();

    @Test
    void shouldPreserveCurrentBootstrapDefaults() {
        assertThat(catalog.defaults(RuntimeConfigType.SEARCH))
                .containsEntry("rankConstant", "60")
                .containsEntry("fusionAlpha", "0.6");
        assertThat(catalog.defaults(RuntimeConfigType.CONVERSATION))
                .containsEntry("intentRoutingEnabled", "false")
                .containsEntry("intentTimeoutSeconds", "5");
        assertThat(catalog.defaults(RuntimeConfigType.AGENT))
                .containsEntry("maxSteps", "12")
                .containsEntry("summaryMaxDocuments", "3");
        assertThat(catalog.defaults(RuntimeConfigType.INGESTION))
                .containsEntry("claimBatchSize", "32")
                .containsEntry("doclingMaxResponseMiB", "256");
        assertThat(catalog.defaults(RuntimeConfigType.OUTBOX))
                .containsEntry("retentionDays", "90");
    }

    @Test
    void shouldRejectUnknownAndInvalidValues() {
        assertThatThrownBy(() -> catalog.normalize(
                RuntimeConfigType.AGENT, "unknown", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported runtime config key");
        assertThatThrownBy(() -> catalog.normalize(
                RuntimeConfigType.AGENT, "enabled", "yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
        assertThatThrownBy(() -> catalog.normalize(
                RuntimeConfigType.SEARCH, "fusionAlpha", "1.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
    }

    @Test
    void shouldValidateCrossFieldRulesAfterPartialMerge() {
        LinkedHashMap<String, String> search =
                new LinkedHashMap<>(catalog.defaults(RuntimeConfigType.SEARCH));
        search.put("windowMin", "81");

        assertThatThrownBy(() ->
                catalog.validateResolved(RuntimeConfigType.SEARCH, search))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("windowMin must not exceed windowMax");

        LinkedHashMap<String, String> agent =
                new LinkedHashMap<>(catalog.defaults(RuntimeConfigType.AGENT));
        agent.put("summaryBatchChars", "500001");

        assertThatThrownBy(() ->
                catalog.validateResolved(RuntimeConfigType.AGENT, agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("summaryBatchChars must not exceed summaryMaxChars");
    }
}
