package com.anchr.core.settings.application;

import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConfigUpdateRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestRequestDTO;
import com.anchr.core.settings.interfaces.rest.dto.CapabilityConnectionTestResultDTO;

import java.util.Optional;

/**
 * Application service for capability configuration.
 */
public interface CapabilityConfigService {

    String CAPABILITY_EMBEDDING = "EMBEDDING";
    String CAPABILITY_GENERATION = "GENERATION";
    String CAPABILITY_RERANK = "RERANK";
    String CAPABILITY_MULTI_EMBEDDING = "MULTI_EMBEDDING";

    Optional<CapabilityConfigDTO> get(String capability);

    CapabilityConfigDTO save(String capability, CapabilityConfigUpdateRequestDTO request);

    CapabilityConnectionTestResultDTO test(CapabilityConnectionTestRequestDTO request);
}
