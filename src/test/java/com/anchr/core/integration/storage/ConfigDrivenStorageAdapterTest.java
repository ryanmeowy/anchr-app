package com.anchr.core.integration.storage;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.settings.domain.model.StorageConfig;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigDrivenStorageAdapterTest {

    @Mock
    private StorageConfigRepository configRepository;
    @Mock
    private AesUtil aesUtil;

    private ConfigDrivenStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ConfigDrivenStorageAdapter(configRepository, aesUtil);
    }

    @Test
    void deleteObjectRejectsABlankKey() {
        assertThrows(IllegalArgumentException.class, () -> adapter.deleteObject(" "));
    }

    @Test
    void deleteObjectRejectsAKeyOutsideTheConfiguredPrefix() {
        when(configRepository.find()).thenReturn(Optional.of(config("tenant-a")));

        assertThrows(IllegalArgumentException.class,
                () -> adapter.deleteObject("tenant-b/file.pdf"));
    }

    @Test
    void deleteObjectNormalizesTheConfiguredPrefixBeforeChecking() {
        when(configRepository.find()).thenReturn(Optional.of(config("/tenant-a/")));

        assertThrows(IllegalArgumentException.class,
                () -> adapter.deleteObject("tenant-b/file.pdf"));
    }

    private StorageConfig config(String prefix) {
        return StorageConfig.builder()
                .endpoint("https://oss.example.com")
                .bucket("bucket")
                .region("cn-test")
                .prefix(prefix)
                .accessKeyEnc("encrypted-access-key")
                .secretKeyEnc("encrypted-secret-key")
                .enabled(true)
                .build();
    }
}
