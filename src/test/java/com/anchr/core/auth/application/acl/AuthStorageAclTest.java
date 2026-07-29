package com.anchr.core.auth.application.acl;

import com.anchr.core.settings.application.api.StorageRuntimeApi;
import com.anchr.core.settings.application.api.model.StorageLocationSnapshot;
import com.anchr.core.settings.application.api.model.StorageTemporaryCredential;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthStorageAclTest {

    private final StorageRuntimeApi storageRuntimeApi =
            mock(StorageRuntimeApi.class);
    private final AuthStorageAcl acl = new AuthStorageAcl(storageRuntimeApi);

    @Test
    void shouldMapCapabilityCredentialWithoutChangingResponseKeys() {
        when(storageRuntimeApi.findLocation()).thenReturn(Optional.of(
                new StorageLocationSnapshot("https://oss", "bucket", "cn-test", "uploads/")));
        when(storageRuntimeApi.issueTemporaryCredential()).thenReturn(
                new StorageTemporaryCredential(
                        "https://oss", "bucket", "cn-test", "uploads/",
                        "ak", "sk", "token", "expiry"));

        assertThatCode(acl::requireConfigured).doesNotThrowAnyException();
        assertThat(acl.issueUploadCredential().toResponseMap())
                .containsExactly(
                        org.assertj.core.api.Assertions.entry("endpoint", "https://oss"),
                        org.assertj.core.api.Assertions.entry("bucket", "bucket"),
                        org.assertj.core.api.Assertions.entry("region", "cn-test"),
                        org.assertj.core.api.Assertions.entry("prefix", "uploads/"),
                        org.assertj.core.api.Assertions.entry("accessKeyId", "ak"),
                        org.assertj.core.api.Assertions.entry("accessKeySecret", "sk"),
                        org.assertj.core.api.Assertions.entry("securityToken", "token"),
                        org.assertj.core.api.Assertions.entry("expiration", "expiry"));
    }

    @Test
    void shouldKeepMissingConfigurationFailureOutsideIssuance() {
        when(storageRuntimeApi.findLocation()).thenReturn(Optional.empty());

        assertThatThrownBy(acl::requireConfigured)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Object storage is not configured.");
    }
}
