package com.anchr.core.common.config;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.application.api.RuntimeConfigQueryApi;
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

        assertThat(unit.getString("AGENT", "toolCallMode", "AUTO"))
                .isEqualTo("AUTO");
        assertThat(unit.getBoolean("AGENT", "enabled", true)).isTrue();
        assertThat(unit.getInt("AGENT", "maxSteps", 12)).isEqualTo(12);
        assertThat(unit.getLong("OUTBOX", "retentionDays", 90L)).isEqualTo(90L);
        assertThat(unit.getFloat("SEARCH", "textSimilarity", 0.75F))
                .isEqualTo(0.75F);
        assertThat(unit.getDouble("SEARCH", "fusionAlpha", 0.6D))
                .isEqualTo(0.6D);
        assertThat(unit.getDurationSeconds(
                "AGENT", "totalTimeoutSeconds", Duration.ofSeconds(90)))
                .isEqualTo(Duration.ofSeconds(90));
        assertThat(unit.getDurationMinutes(
                "INGESTION", "parseStageTimeoutMinutes", Duration.ofMinutes(45)))
                .isEqualTo(Duration.ofMinutes(45));
        assertThat(unit.getEnum(
                "AGENT", "toolCallMode", Mode.class, Mode.AUTO))
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
                Optional.ofNullable(values.get(type + "." + key));
        RuntimeConfigUnit unit = new RuntimeConfigUnit(api);

        assertThat(unit.getDouble("SEARCH", "fusionAlpha", 0.6D))
                .isEqualTo(0.35D);
        assertThat(unit.getBoolean("AGENT", "enabled", true)).isFalse();
        assertThat(unit.getInt("AGENT", "maxSteps", 12)).isEqualTo(20);
        assertThat(unit.getDurationSeconds(
                "AGENT", "totalTimeoutSeconds", Duration.ofSeconds(90)))
                .isEqualTo(Duration.ofSeconds(120));
        assertThat(unit.getEnum(
                "AGENT", "toolCallMode", Mode.class, Mode.AUTO))
                .isEqualTo(Mode.NATIVE);
    }

    @Test
    void shouldFailExplicitlyWithoutIncludingTheInvalidValue() {
        RuntimeConfigUnit unit = new RuntimeConfigUnit(
                (type, key) -> Optional.of("secret-invalid-value"));

        assertThatThrownBy(() ->
                unit.getInt("AGENT", "maxSteps", 12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type=AGENT")
                .hasMessageContaining("key=maxSteps")
                .hasMessageNotContaining("secret-invalid-value");
        assertThatThrownBy(() ->
                unit.getBoolean("AGENT", "enabled", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key=enabled")
                .hasMessageNotContaining("secret-invalid-value");
    }

    private enum Mode {
        AUTO, NATIVE
    }
}
