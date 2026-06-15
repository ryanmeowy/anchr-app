package com.anchr.core.settings.domain.repository;

import com.anchr.core.settings.domain.model.CapabilityConfig;

import java.util.List;
import java.util.Optional;

/**
 * Repository for capability configuration.
 */
public interface CapabilityConfigRepository {

    List<CapabilityConfig> findByCapability(String capability);

    List<CapabilityConfig> findAllByCapability(String capability);

    Optional<CapabilityConfig> findById(Long id);

    CapabilityConfig insert(CapabilityConfig config);

    CapabilityConfig update(CapabilityConfig config);

    void select(String capability, Long id);

    void disableAll(String capability);

    void del(String capability, Long id);
}
