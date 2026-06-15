package com.anchr.core.settings.domain.repository;

import com.anchr.core.settings.domain.model.CapabilityConfig;

import java.util.List;

/**
 * Repository for capability configuration.
 */
public interface CapabilityConfigRepository {

    List<CapabilityConfig> findByCapability(String capability);

    CapabilityConfig upsert(CapabilityConfig config);

    void del(String capability, Long id);
}
