package com.anchr.core.settings.application;

import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestResultDTO;

import java.util.List;

/**
 * Application service for capability configuration.
 */
public interface CapabilityConfigService {

    String CAPABILITY_EMBEDDING = "EMBEDDING";
    String CAPABILITY_GENERATION = "GENERATION";
    String CAPABILITY_RERANK = "RERANK";
    String CAPABILITY_MULTI_EMBEDDING = "MULTI_EMBEDDING";

    List<CapabilityConfigDTO> get(String capability);

    List<CapabilityConfigDTO> findAll(String capability);

    CapabilityConfigDTO create(String capability, CapabilityConfigUpdateRequestDTO request);

    CapabilityConfigDTO update(String capability, Long id, CapabilityConfigUpdateRequestDTO request);

    CapabilityConnectionTestResultDTO test(CapabilityConnectionTestRequestDTO request);

    void select(String capability, Long id);

    void del(String capability, Long id);
}
