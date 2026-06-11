package com.anchr.core.settings.domain.repository;

import com.anchr.core.settings.domain.model.CapabilityConfig;

import java.util.Optional;

/**
 * Repository for capability configuration.
 */
public interface CapabilityConfigRepository {

    Optional<CapabilityConfig> findByCapability(String capability);

    CapabilityConfig upsert(CapabilityConfig config);
}
