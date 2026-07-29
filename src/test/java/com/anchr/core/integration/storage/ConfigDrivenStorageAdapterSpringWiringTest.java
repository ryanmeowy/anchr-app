package com.anchr.core.integration.storage;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.ingestion.domain.port.IngestionObjectStoragePort;
import com.anchr.core.kb.domain.port.KnowledgeObjectStoragePort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
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

            ConfigDrivenStorageAdapter adapter =
                    context.getBean(ConfigDrivenStorageAdapter.class);
            assertThat(context.getBean(SearchObjectStoragePort.class))
                    .isSameAs(adapter);
            assertThat(context.getBean(IngestionObjectStoragePort.class))
                    .isSameAs(adapter);
            assertThat(context.getBean(KnowledgeObjectStoragePort.class))
                    .isSameAs(adapter);
        }
    }
}
