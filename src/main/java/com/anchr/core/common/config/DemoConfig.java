package com.anchr.core.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the demo property binding.
 *
 * <p>Kept as its own configuration class so the {@code demo} branch touches no shared bootstrap
 * code; removing the branch removes the feature with no leftovers in {@code AnchrApplication}.
 */
@Configuration
@EnableConfigurationProperties(DemoProperties.class)
public class DemoConfig {
}
