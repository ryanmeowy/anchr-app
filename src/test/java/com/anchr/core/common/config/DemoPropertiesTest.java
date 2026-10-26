package com.anchr.core.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoPropertiesTest {

    @Test
    void defaultsShouldKeepTheDemoOff() {
        DemoProperties properties = DemoProperties.defaults();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.tokenTtlHours()).isEqualTo(5);
        assertThat(properties.tokenQuota()).isEqualTo(8);
        assertThat(properties.dailyOperationLimit()).isEqualTo(80);
        assertThat(properties.dailyTokenLimit()).isEqualTo(50);
        assertThat(properties.issueCooldownMinutes()).isEqualTo(10);
        assertThat(properties.uploadMaxFiles()).isEqualTo(1);
        assertThat(properties.turnstileEnabled()).isTrue();
        assertThat(properties.turnstileVerificationActive()).isFalse();
    }

    @Test
    void mustRejectNonPositiveLimits() {
        assertThatThrownBy(() -> new DemoProperties(
                false, 0, 8, 80, 50, 10, 1, true, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-ttl-hours");
        assertThatThrownBy(() -> new DemoProperties(
                false, 5, 0, 80, 50, 10, 1, true, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-quota");
        assertThatThrownBy(() -> new DemoProperties(
                false, 5, 8, 0, 50, 10, 1, true, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily-operation-limit");
        assertThatThrownBy(() -> new DemoProperties(
                false, 5, 8, 80, 0, 10, 1, true, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily-token-limit");
        assertThatThrownBy(() -> new DemoProperties(
                false, 5, 8, 80, 50, 10, 0, true, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upload-max-files");
    }

    @Test
    void mustRejectNegativeCooldown() {
        assertThatThrownBy(() -> new DemoProperties(
                false, 5, 8, 80, 50, -1, 1, true, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issue-cooldown-minutes");
    }

    @Test
    void mustRequireSecretWhenEnabledWithVerification() {
        assertThatThrownBy(() -> new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, true, "site", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("turnstile-secret-key");
    }

    @Test
    void shouldAllowEnabledDemoWithoutSecretWhenVerificationIsOff() {
        DemoProperties properties = new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, false, "", "");

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.turnstileVerificationActive()).isFalse();
    }

    @Test
    void verificationShouldRequireBothSwitchesOn() {
        DemoProperties enabled = new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, true, "site", "secret-value-1234");

        assertThat(enabled.turnstileVerificationActive()).isTrue();
        assertThat(enabled.hasTurnstileSecret()).isTrue();
    }
}
