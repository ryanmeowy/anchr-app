package com.anchr.core.common.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The record must bind from the kebab-case keys that {@code application.yaml} and the
 * {@code APP_DEMO_*} environment variables produce. A mismatch would not fail at startup; it
 * would silently leave every limit at its default, which is the worst possible failure mode for
 * a quota.
 */
class DemoPropertiesBindingTest {

    private DemoProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("app.demo", DemoProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.demo did not bind"));
    }

    @Test
    void shouldBindFromTheSameKebabCaseKeysTheYamlUses() {
        DemoProperties properties = bind(Map.ofEntries(
                Map.entry("app.demo.enabled", "true"),
                Map.entry("app.demo.token-ttl-hours", "6"),
                Map.entry("app.demo.token-quota", "12"),
                Map.entry("app.demo.daily-operation-limit", "150"),
                Map.entry("app.demo.daily-token-limit", "70"),
                Map.entry("app.demo.issue-cooldown-minutes", "3"),
                Map.entry("app.demo.upload-max-files", "2"),
                Map.entry("app.demo.turnstile-enabled", "true"),
                Map.entry("app.demo.turnstile-site-key", "site-key"),
                Map.entry("app.demo.turnstile-secret-key", "secret-key-value"),
                Map.entry("app.demo.start-closed", "true")));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.tokenTtlHours()).isEqualTo(6);
        assertThat(properties.tokenQuota()).isEqualTo(12);
        assertThat(properties.dailyOperationLimit()).isEqualTo(150);
        assertThat(properties.dailyTokenLimit()).isEqualTo(70);
        assertThat(properties.issueCooldownMinutes()).isEqualTo(3);
        assertThat(properties.uploadMaxFiles()).isEqualTo(2);
        assertThat(properties.turnstileVerificationActive()).isTrue();
        assertThat(properties.siteKey()).isEqualTo("site-key");
        assertThat(properties.startClosed()).isTrue();
    }

    @Test
    void shouldFallBackToTheRecordDefaultsWhenNoSourceIsPresent() {
        // Production case: the demo keys are absent. Binding yields nothing, and the record's
        // @DefaultValue annotations keep every limit at its safe default.
        assertThat(new Binder(new MapConfigurationPropertySource(Map.of()))
                .bind("app.demo", DemoProperties.class)
                .isBound())
                .isFalse();
        assertThat(DemoProperties.defaults().enabled()).isFalse();
        assertThat(DemoProperties.defaults().tokenQuota()).isEqualTo(8);
        assertThat(DemoProperties.defaults().dailyOperationLimit()).isEqualTo(80);
        assertThat(DemoProperties.defaults().uploadMaxFiles()).isEqualTo(1);
    }

    @Test
    void shouldRejectAnEnabledDemoThatForgetsTheSecret() {
        Map<String, String> source = Map.of(
                "app.demo.enabled", "true",
                "app.demo.turnstile-enabled", "true");

        // The failure surfaces as a bind exception wrapping the record's validation message.
        assertThatThrownBy(() -> bind(source))
                .hasMessageContaining("Failed to bind properties under 'app.demo'");
    }
}
