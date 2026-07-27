package com.anchr.core.ingestion.application.impl;

import com.anchr.core.common.model.ParseRequest;
import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionParseRequestSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void snapshotExcludesSignedUrlAndCredentialsButRebuildsV3Request() throws Exception {
        IngestionParseRequestSnapshot snapshot = IngestionParseRequestSnapshot.capture(
                asset(), true, storageConfig(), "task-1", "item-1", 1);

        String json = objectMapper.writeValueAsString(snapshot);
        IngestionParseRequestSnapshot restored = objectMapper.readValue(
                json, IngestionParseRequestSnapshot.class).validated();
        ParseRequest request = restored.toRequest(
                "task-1:item-1:1",
                "v1:revision",
                "https://signed.example.test/source.pdf?Expires=2",
                Map.of("iv", "iv-2", "ciphertext", "cipher-2"));

        assertThat(json)
                .doesNotContain("signed.example.test")
                .doesNotContain("cipher-2")
                .doesNotContain("iv-2");
        assertThat(request.contractVersion()).isEqualTo(3);
        assertThat(request.options().includeEmbeddedImages()).isTrue();
        assertThat(request.oss().endpoint()).isEqualTo("oss.example.test");
        assertThat(request.oss().basePath())
                .isEqualTo("embedded/ingestion/task-1/item-1/parse/1/images/");
        assertThat(request.oss().objectKeyLayout()).isEqualTo("ATTEMPT_PREFIX_V1");
        assertThat(request.oss().encryptedCredentials())
                .containsEntry("ciphertext", "cipher-2");
    }

    @Test
    void disabledEmbeddedImagesDoNotCaptureStorageTargetOrRequireCredentials() {
        IngestionParseRequestSnapshot snapshot = IngestionParseRequestSnapshot.capture(
                asset(), false, storageConfig(), "task-1", "item-1", 1);

        ParseRequest request = snapshot.toRequest(
                "task-1:item-1:1",
                "v1:revision",
                "https://signed.example.test/source.pdf",
                null);

        assertThat(request.options().includeEmbeddedImages()).isFalse();
        assertThat(request.oss()).isNull();
    }

    @Test
    void persistedOutputTargetRejectsChangedStorageConfiguration() {
        IngestionParseRequestSnapshot snapshot = IngestionParseRequestSnapshot.capture(
                asset(), true, storageConfig(), "task-1", "item-1", 1);
        StorageConfig changed = StorageConfig.builder()
                .endpoint("oss.example.test")
                .bucket("another-bucket")
                .prefix("embedded/")
                .build();

        assertThat(snapshot.targets(changed, "task-1", "item-1", 1)).isFalse();
        assertThatThrownBy(() -> snapshot.toRequest(
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
