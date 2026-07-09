package com.anchr.core.settings.application;

import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestResultDTO;

import java.util.List;
import java.util.Optional;

/**
 * Application service for capability configuration.
 */
public interface CapabilityConfigService {

    List<CapabilityConfigDTO> get(String capability);

    List<CapabilityConfigDTO> findAll(String capability);

    CapabilityConfigDTO create(String capability, CapabilityConfigUpdateRequestDTO request);

    CapabilityConfigDTO update(String capability, Long id, CapabilityConfigUpdateRequestDTO request);

    CapabilityConnectionTestResultDTO test(CapabilityConnectionTestRequestDTO request);

    void select(String capability, Long id);

    void del(String capability, Long id);
}
