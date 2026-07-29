package com.anchr.core.settings.application.impl;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.integration.storage.StorageTokenIssuer;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageRuntimeServiceImplTest {

    private final StorageConfigRepository repository =
            mock(StorageConfigRepository.class);
    private final AesUtil aesUtil = mock(AesUtil.class);
    private final StorageTokenIssuer tokenIssuer =
            mock(StorageTokenIssuer.class);
    private final StorageRuntimeServiceImpl service =
            new StorageRuntimeServiceImpl(repository, aesUtil, tokenIssuer);

    @Test
    void shouldExposeOnlyLocationFacts() {
        when(repository.find()).thenReturn(Optional.of(config()));

        var location = service.findLocation().orElseThrow();

        assertThat(location.endpoint()).isEqualTo("https://oss");
        assertThat(location.bucket()).isEqualTo("bucket");
        assertThat(location.region()).isEqualTo("cn-test");
        assertThat(location.prefix()).isEqualTo("uploads/");
    }

    @Test
    void shouldDecryptInternallyAndReturnTypedTemporaryCredential() {
        StorageConfig config = config();
        when(repository.find()).thenReturn(Optional.of(config));
        when(aesUtil.decrypt("ak-enc")).thenReturn("ak");
        when(aesUtil.decrypt("sk-enc")).thenReturn("sk");
        Map<String, Object> issued = new LinkedHashMap<>();
        issued.put("endpoint", "https://oss");
        issued.put("bucket", "bucket");
        issued.put("region", "cn-test");
        issued.put("prefix", "uploads/");
        issued.put("accessKeyId", "temp-ak");
        issued.put("accessKeySecret", "temp-sk");
        issued.put("securityToken", "token");
        issued.put("expiration", "2026-07-29T19:00:00Z");
        when(tokenIssuer.issueToken(config, "ak", "sk")).thenReturn(issued);

        var credential = service.issueTemporaryCredential();

        assertThat(credential.accessKeyId()).isEqualTo("temp-ak");
        assertThat(credential.accessKeySecret()).isEqualTo("temp-sk");
        assertThat(credential.securityToken()).isEqualTo("token");
        assertThat(credential.expiration()).isEqualTo("2026-07-29T19:00:00Z");
        verify(tokenIssuer).issueToken(config, "ak", "sk");
    }

    @Test
    void shouldKeepMissingConfigurationFailure() {
        when(repository.find()).thenReturn(Optional.empty());

        assertThatThrownBy(service::issueTemporaryCredential)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Object storage is not configured.");
    }

    private StorageConfig config() {
        return StorageConfig.builder()
                .endpoint("https://oss")
                .bucket("bucket")
                .region("cn-test")
                .prefix("uploads/")
                .accessKeyEnc("ak-enc")
                .secretKeyEnc("sk-enc")
                .roleArn("role")
                .build();
    }
}
