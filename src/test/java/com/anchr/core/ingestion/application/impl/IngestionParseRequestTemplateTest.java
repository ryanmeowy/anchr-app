package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.settings.domain.model.StorageConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionParseRequestTemplateTest {

    @Test
    void templateRebuildsV3RequestWithRuntimeUrlAndCredentials() {
        IngestionParseRequestTemplate template = IngestionParseRequestTemplate.capture(
                asset(), true, storageConfig(), "asset-1", 1);

        ParseRequest request = template.toRequest(
                "task-1:item-1:1",
                "v1:revision",
                "https://signed.example.test/source.pdf?Expires=2",
                Map.of("iv", "iv-2", "ciphertext", "cipher-2"));

        assertThat(request.contractVersion()).isEqualTo(3);
        assertThat(request.sourceUrl()).contains("signed.example.test");
        assertThat(request.options().includeEmbeddedImages()).isTrue();
        assertThat(request.oss().endpoint()).isEqualTo("oss.example.test");
        assertThat(request.oss().basePath())
                .isEqualTo("embedded/ingestion/assets/asset-1/generations/1/images/");
        assertThat(request.oss().objectKeyLayout()).isEqualTo("ATTEMPT_PREFIX_V1");
        assertThat(request.oss().encryptedCredentials())
                .containsEntry("ciphertext", "cipher-2");
    }

    @Test
    void disabledEmbeddedImagesDoNotCaptureStorageTargetOrRequireCredentials() {
        IngestionParseRequestTemplate template = IngestionParseRequestTemplate.capture(
                asset(), false, storageConfig(), "asset-1", 1);

        ParseRequest request = template.toRequest(
                "task-1:item-1:1",
                "v1:revision",
                "https://signed.example.test/source.pdf",
                null);

        assertThat(request.options().includeEmbeddedImages()).isFalse();
        assertThat(request.oss()).isNull();
    }

    @Test
    void inMemoryOutputTargetRejectsChangedStorageConfiguration() {
        IngestionParseRequestTemplate template = IngestionParseRequestTemplate.capture(
                asset(), true, storageConfig(), "asset-1", 1);
        StorageConfig changed = StorageConfig.builder()
                .endpoint("oss.example.test")
                .bucket("another-bucket")
                .prefix("embedded/")
                .build();

        assertThat(template.targets(changed, "asset-1", 1)).isFalse();
        assertThatThrownBy(() -> template.toRequest(
                "task-1:item-1:1",
                "v1:revision",
                "https://signed.example.test/source.pdf",
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials");
    }

    private Asset asset() {
        return Asset.builder()
                .id("asset-1")
                .fileName("invoice.pdf")
                .build();
    }

    private StorageConfig storageConfig() {
        return StorageConfig.builder()
                .endpoint("oss.example.test")
                .bucket("bucket-a")
                .prefix("embedded/")
                .build();
    }
}
