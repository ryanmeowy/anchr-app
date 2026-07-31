package com.anchr.core.common.config;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.application.api.RuntimeConfigQueryApi;
import com.anchr.core.settings.domain.model.AgentRuntimeConfigKey;
import com.anchr.core.settings.domain.model.IngestionRuntimeConfigKey;
import com.anchr.core.settings.domain.model.OutboxRuntimeConfigKey;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.settings.domain.model.SearchRuntimeConfigKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigUnitTest {

    @Test
    void shouldReturnCallerDefaultsOnlyWhenTheSupportedValueIsMissing() {
        RuntimeConfigUnit unit = new RuntimeConfigUnit((type, key) -> Optional.empty());

        assertThat(unit.getString(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.TOOL_CALL_MODE, "AUTO"))
                .isEqualTo("AUTO");
        assertThat(unit.getBoolean(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.ENABLED, true)).isTrue();
        assertThat(unit.getInt(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS, 12))
                .isEqualTo(12);
        assertThat(unit.getLong(
                RuntimeConfigType.OUTBOX, OutboxRuntimeConfigKey.RETENTION_DAYS, 90L))
                .isEqualTo(90L);
        assertThat(unit.getFloat(
                RuntimeConfigType.SEARCH,
                SearchRuntimeConfigKey.TEXT_SIMILARITY,
                0.75F))
                .isEqualTo(0.75F);
        assertThat(unit.getDouble(
                RuntimeConfigType.SEARCH, SearchRuntimeConfigKey.FUSION_ALPHA, 0.6D))
                .isEqualTo(0.6D);
        assertThat(unit.getDurationSeconds(
                RuntimeConfigType.AGENT,
                AgentRuntimeConfigKey.TOTAL_TIMEOUT_SECONDS,
                Duration.ofSeconds(90)))
                .isEqualTo(Duration.ofSeconds(90));
        assertThat(unit.getDurationMinutes(
                RuntimeConfigType.INGESTION,
                IngestionRuntimeConfigKey.PARSE_STAGE_TIMEOUT_MINUTES,
                Duration.ofMinutes(45)))
                .isEqualTo(Duration.ofMinutes(45));
        assertThat(unit.getEnum(
                RuntimeConfigType.AGENT,
                AgentRuntimeConfigKey.TOOL_CALL_MODE,
                Mode.class,
                Mode.AUTO))
                .isEqualTo(Mode.AUTO);
    }

    @Test
    void shouldParsePersistedValuesWithTheRequestedType() {
        Map<String, String> values = Map.of(
                "SEARCH.fusionAlpha", "0.35",
                "AGENT.enabled", "false",
                "AGENT.maxSteps", "20",
                "AGENT.totalTimeoutSeconds", "120",
                "AGENT.toolCallMode", "native");
        RuntimeConfigQueryApi api = (type, key) ->
                Optional.ofNullable(values.get(type + "." + key.propertyName()));
        RuntimeConfigUnit unit = new RuntimeConfigUnit(api);

        assertThat(unit.getDouble(
                RuntimeConfigType.SEARCH, SearchRuntimeConfigKey.FUSION_ALPHA, 0.6D))
                .isEqualTo(0.35D);
        assertThat(unit.getBoolean(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.ENABLED, true)).isFalse();
        assertThat(unit.getInt(
                RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS, 12))
                .isEqualTo(20);
        assertThat(unit.getDurationSeconds(
                RuntimeConfigType.AGENT,
                AgentRuntimeConfigKey.TOTAL_TIMEOUT_SECONDS,
                Duration.ofSeconds(90)))
                .isEqualTo(Duration.ofSeconds(120));
        assertThat(unit.getEnum(
                RuntimeConfigType.AGENT,
                AgentRuntimeConfigKey.TOOL_CALL_MODE,
                Mode.class,
                Mode.AUTO))
                .isEqualTo(Mode.NATIVE);
    }

    @Test
    void shouldFailExplicitlyWithoutIncludingTheInvalidValue() {
        RuntimeConfigUnit unit = new RuntimeConfigUnit(
                (type, key) -> Optional.of("secret-invalid-value"));

        assertThatThrownBy(() ->
                unit.getInt(
                        RuntimeConfigType.AGENT, AgentRuntimeConfigKey.MAX_STEPS, 12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type=AGENT")
                .hasMessageContaining("key=maxSteps")
                .hasMessageNotContaining("secret-invalid-value");
        assertThatThrownBy(() ->
                unit.getBoolean(
                        RuntimeConfigType.AGENT, AgentRuntimeConfigKey.ENABLED, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key=enabled")
                .hasMessageNotContaining("secret-invalid-value");
    }

    @Test
    void shouldRejectAKeyFromAnotherType() {
        RuntimeConfigUnit unit = new RuntimeConfigUnit(
                (type, key) -> Optional.empty());

        assertThatThrownBy(() -> unit.getInt(
                RuntimeConfigType.SEARCH, AgentRuntimeConfigKey.MAX_STEPS, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to SEARCH");
    }

    private enum Mode {
        AUTO, NATIVE
    }
}
