package com.anchr.core.ingestion.application.acl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.application.model.IngestionStorageTarget;
import com.anchr.core.settings.application.api.StorageRuntimeApi;
import com.anchr.core.settings.application.api.model.StorageLocationSnapshot;
import com.anchr.core.settings.application.api.model.StorageTemporaryCredential;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionStorageAclTest {

    private final StorageRuntimeApi storageRuntimeApi =
            mock(StorageRuntimeApi.class);
    private final IngestionStorageAcl acl =
            new IngestionStorageAcl(storageRuntimeApi);

    @Test
    void shouldCaptureStableTargetAndIssueFreshCredential() {
        when(storageRuntimeApi.findLocation()).thenReturn(Optional.of(location("bucket")));
        when(storageRuntimeApi.issueTemporaryCredential()).thenReturn(
                credential("bucket"));

        IngestionStorageTarget target =
                acl.findTarget("asset-1", 4L).orElseThrow();
        var issued = acl.issueTemporaryCredential(target, "asset-1", 4L);

        assertThat(target.basePath()).isEqualTo(
                "embedded/ingestion/assets/asset-1/generations/4/images/");
        assertThat(issued.toCredentialMap())
                .containsEntry("accessKeyId", "temp-ak")
                .containsEntry("expiration", "expiry");
    }

    @Test
    void shouldFailClosedWhenLocationChangesBeforeIssuance() {
        when(storageRuntimeApi.findLocation()).thenReturn(
                Optional.of(location("changed-bucket")));
        IngestionStorageTarget expected = new IngestionStorageTarget(
                "https://oss",
                "bucket",
                "embedded/ingestion/assets/asset-1/generations/4/images/",
                "ATTEMPT_PREFIX_V1");

        assertThatThrownBy(() ->
                acl.issueTemporaryCredential(expected, "asset-1", 4L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getError()).isEqualTo(ApiError.INTERNAL_ERROR);
                    assertThat(error.getMessage()).contains(
                            "Storage output target changed");
                });
    }

    @Test
    void shouldFailClosedWhenIssuedCredentialUsesAnotherTarget() {
        when(storageRuntimeApi.findLocation()).thenReturn(Optional.of(location("bucket")));
        when(storageRuntimeApi.issueTemporaryCredential()).thenReturn(
                credential("changed-bucket"));
        IngestionStorageTarget expected =
                acl.findTarget("asset-1", 4L).orElseThrow();

        assertThatThrownBy(() ->
                acl.issueTemporaryCredential(expected, "asset-1", 4L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getMessage()).contains(
                                "Storage output target changed"));
    }

    @Test
    void shouldKeepMissingConfigurationFailureMessage() {
        when(storageRuntimeApi.findLocation()).thenReturn(Optional.empty());
        IngestionStorageTarget expected = new IngestionStorageTarget(
                "https://oss", "bucket", "path/", "ATTEMPT_PREFIX_V1");

        assertThatThrownBy(() ->
                acl.issueTemporaryCredential(expected, "asset-1", 4L))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getError()).isEqualTo(ApiError.INTERNAL_ERROR);
                    assertThat(error.getMessage()).contains(
                            "Docling image output requires storage configuration");
                });
    }

    private StorageLocationSnapshot location(String bucket) {
        return new StorageLocationSnapshot(
                "https://oss", bucket, "cn-test", "embedded/");
    }

    private StorageTemporaryCredential credential(String bucket) {
        return new StorageTemporaryCredential(
                "https://oss", bucket, "cn-test", "embedded/",
                "temp-ak", "temp-sk", "token", "expiry");
    }
}
