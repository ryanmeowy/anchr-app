package com.anchr.core.ingestion.interfaces.rest.dto;

import com.anchr.core.ingestion.application.IngestionCapabilityService;
import com.anchr.core.ingestion.application.constant.IngestionConstant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionCapabilityDTOTest {

    @Test
    void shouldExposeBothRegularAndImageFileLimits() {
        IngestionCapabilityDTO dto = IngestionCapabilityDTO.from(
                new IngestionCapabilityService().getCapabilities());

        assertThat(dto.getMaxFileSizeBytes())
                .isEqualTo(IngestionConstant.MAX_FILE_SIZE_BYTES);
        assertThat(dto.getMaxImageFileSizeBytes())
                .isEqualTo(IngestionConstant.MAX_IMAGE_FILE_SIZE_BYTES);
    }
}
