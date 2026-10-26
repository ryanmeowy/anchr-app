package com.anchr.core.ingestion.application;

import com.anchr.core.common.config.DemoProperties;
import com.anchr.core.ingestion.application.constant.IngestionConstant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionCapabilityDemoLimitTest {

    @Test
    void productionDeploymentKeepsTheWideBatchLimit() {
        IngestionCapabilityService service =
                new IngestionCapabilityService(DemoProperties.defaults());

        assertThat(service.maxFilesPerBatch())
                .isEqualTo(IngestionConstant.MAX_FILES_PER_BATCH);
        assertThat(service.getCapabilities().getMaxFilesPerBatch())
                .isEqualTo(IngestionConstant.MAX_FILES_PER_BATCH);
    }

    @Test
    void demoDeploymentNarrowsTheBatchLimitToOneFile() {
        DemoProperties demo = new DemoProperties(
                true, 5, 8, 80, 50, 10, 1, false, "", "");
        IngestionCapabilityService service = new IngestionCapabilityService(demo);

        assertThat(service.maxFilesPerBatch()).isEqualTo(1);
        // The declaration must match enforcement, otherwise the UI advertises the wrong limit.
        assertThat(service.getCapabilities().getMaxFilesPerBatch()).isEqualTo(1);
    }
}
