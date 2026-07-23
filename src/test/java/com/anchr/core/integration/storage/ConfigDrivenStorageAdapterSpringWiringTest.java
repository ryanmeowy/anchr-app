package com.anchr.core.integration.storage;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.settings.domain.repository.StorageConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConfigDrivenStorageAdapterSpringWiringTest {

    @Test
    void springContext_shouldResolveTheSingleProductionConstructor() {
        StorageConfigRepository configRepository =
                mock(StorageConfigRepository.class);
        AesUtil aesUtil = mock(AesUtil.class);

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    StorageConfigRepository.class, () -> configRepository);
            context.registerBean(AesUtil.class, () -> aesUtil);
            context.register(ConfigDrivenStorageAdapter.class);
            context.refresh();

            assertThat(context.getBean(ConfigDrivenStorageAdapter.class))
                    .isNotNull();
        }
    }
}
