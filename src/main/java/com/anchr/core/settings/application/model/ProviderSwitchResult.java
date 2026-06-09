package com.anchr.core.settings.application.model;

import com.anchr.core.settings.domain.model.ProviderType;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Provider switch result.
 */
@Value
@Builder
public class ProviderSwitchResult {
    ProviderType providerType;
    String providerName;
    int version;
    boolean effectiveImmediately;
    List<String> warnings;
}
