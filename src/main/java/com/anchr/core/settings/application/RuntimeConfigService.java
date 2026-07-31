package com.anchr.core.settings.application;

import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigGroupDTO;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigResponseDTO;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigUpdateRequestDTO;

public interface RuntimeConfigService {

    RuntimeConfigResponseDTO getAll();

    RuntimeConfigGroupDTO update(RuntimeConfigUpdateRequestDTO request);
}
