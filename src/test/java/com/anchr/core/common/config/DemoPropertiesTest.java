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
        assertThat(properties.captchaEnabled()).isTrue();
        assertThat(properties.captchaVerificationActive()).isFalse();
    }

    @Test
    void mustRejectNonPositiveLimits() {
        assertThatThrownBy(() -> new DemoProperties(false, 0, 8, 80, 50, 10, 1, true, "", "", "cn-shanghai", "", "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-ttl-hours");
        assertThatThrownBy(() -> new DemoProperties(false, 5, 0, 80, 50, 10, 1, true, "", "", "cn-shanghai", "", "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-quota");
        assertThatThrownBy(() -> new DemoProperties(false, 5, 8, 0, 50, 10, 1, true, "", "", "cn-shanghai", "", "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily-operation-limit");
        assertThatThrownBy(() -> new DemoProperties(false, 5, 8, 80, 0, 10, 1, true, "", "", "cn-shanghai", "", "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily-token-limit");
        assertThatThrownBy(() -> new DemoProperties(false, 5, 8, 80, 50, 10, 0, true, "", "", "cn-shanghai", "", "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upload-max-files");
    }

    @Test
    void mustRejectNegativeCooldown() {
        assertThatThrownBy(() -> new DemoProperties(false, 5, 8, 80, 50, -1, 1, true, "", "", "cn-shanghai", "", "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issue-cooldown-minutes");
    }

    @Test
    void mustRequireCredentialsWhenEnabledWithVerification() {
        // Scene id missing.
        assertThatThrownBy(() -> new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, true, "", "", "cn-shanghai", "ak", "secret", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scene-id");
    }

    @Test
    void mustRequireAccessKeyWhenEnabledWithVerification() {
        assertThatThrownBy(() -> new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, true, "scene-1", "", "cn-shanghai", "", "secret", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access-key-id");
    }

    @Test
    void mustRequireAccessSecretWhenEnabledWithVerification() {
        assertThatThrownBy(() -> new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, true, "scene-1", "", "cn-shanghai", "ak", "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access-key-secret");
    }

    @Test
    void shouldAllowEnabledDemoWithoutCredentialsWhenVerificationIsOff() {
        DemoProperties properties = new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, false, "", "", "cn-shanghai", "", "", false);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.captchaVerificationActive()).isFalse();
    }

    @Test
    void verificationShouldRequireBothSwitchesOn() {
        DemoProperties enabled = new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, true, "scene-1", "prefix-1", "cn-shanghai", "ak", "secret", false);

        assertThat(enabled.captchaVerificationActive()).isTrue();
        assertThat(enabled.hasCaptchaCredentials()).isTrue();
    }
}
