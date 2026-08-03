package com.anchr.core.ingestion.application;

import com.anchr.core.ingestion.application.constant.IngestionConstant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionCapabilityServiceTest {

    private final IngestionCapabilityService service = new IngestionCapabilityService();

    @Test
    void shouldDeclareBackendOwnedUploadLimits() {
        IngestionCapabilityService.IngestionCapabilities capabilities = service.getCapabilities();

        assertThat(capabilities.getMaxFileSizeBytes())
                .isEqualTo(IngestionConstant.MAX_FILE_SIZE_BYTES);
        assertThat(capabilities.getMaxImageFileSizeBytes())
                .isEqualTo(IngestionConstant.MAX_IMAGE_FILE_SIZE_BYTES);
    }

    @Test
    void shouldResolveImageAndRegularFileLimitsByFileType() {
        assertThat(service.maxFileSizeBytesFor("image"))
                .isEqualTo(IngestionConstant.MAX_IMAGE_FILE_SIZE_BYTES);
        assertThat(service.maxFileSizeBytesFor("PDF"))
                .isEqualTo(IngestionConstant.MAX_FILE_SIZE_BYTES);
    }
}
