package com.anchr.core.kb.application.acl;

import com.anchr.core.settings.application.api.StorageRuntimeApi;
import com.anchr.core.settings.application.api.model.StorageLocationSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeStorageAclTest {

    private final StorageRuntimeApi storageRuntimeApi =
            mock(StorageRuntimeApi.class);
    private final KnowledgeStorageAcl acl =
            new KnowledgeStorageAcl(storageRuntimeApi);

    @Test
    void shouldExposeOnlyConfiguredPrefix() {
        when(storageRuntimeApi.findLocation()).thenReturn(Optional.of(
                new StorageLocationSnapshot(
                        "https://oss", "bucket", "cn-test", "embedded/")));

        assertThat(acl.findConfiguredPrefix()).contains("embedded/");
    }

    @Test
    void shouldKeepMissingConfigurationOptional() {
        when(storageRuntimeApi.findLocation()).thenReturn(Optional.empty());

        assertThat(acl.findConfiguredPrefix()).isEmpty();
    }
}
