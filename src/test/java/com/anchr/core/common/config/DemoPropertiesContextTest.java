package com.anchr.core.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production safety net: a deployment that never sets any {@code app.demo} key must still start
 * and must end up with the demo disabled.
 */
class DemoPropertiesContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(DemoConfig.class);

    @Test
    void shouldCreateTheBeanWithDefaultsWhenNoDemoKeysExist() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DemoProperties.class);

            DemoProperties properties = context.getBean(DemoProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.tokenQuota()).isEqualTo(10);
            assertThat(properties.dailyOperationLimit()).isEqualTo(80);
            assertThat(properties.dailyTokenLimit()).isEqualTo(50);
            assertThat(properties.uploadMaxFiles()).isEqualTo(1);
            assertThat(properties.captchaVerificationActive()).isFalse();
        });
    }

    @Test
    void shouldPickUpPropertyValuesFromTheEnvironment() {
        runner.withPropertyValues(
                "app.demo.enabled=true",
                "app.demo.token-quota=3",
                "app.demo.daily-operation-limit=9",
                "app.demo.captcha-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DemoProperties properties = context.getBean(DemoProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.tokenQuota()).isEqualTo(3);
                    assertThat(properties.dailyOperationLimit()).isEqualTo(9);
                    assertThat(properties.captchaVerificationActive()).isFalse();
                });
    }

    @Test
    void shouldStartWhenTheDemoIsFullyConfigured() {
        runner.withPropertyValues(
                "app.demo.enabled=true",
                "app.demo.captcha-enabled=true",
                "app.demo.captcha-scene-id=scene-1",
                "app.demo.captcha-access-key-id=ak-value"
                , "app.demo.captcha-access-key-secret=secret-value",
                "app.demo.start-closed=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DemoProperties properties = context.getBean(DemoProperties.class);
                    assertThat(properties.captchaVerificationActive()).isTrue();
                    assertThat(properties.startClosed()).isTrue();
                    assertThat(properties.sceneId()).isEqualTo("scene-1");
                });
    }

    @Test
    void shouldFailStartupWhenAnEnabledDemoHasNoCaptchaSceneId() {
        runner.withPropertyValues(
                "app.demo.enabled=true",
                "app.demo.captcha-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
