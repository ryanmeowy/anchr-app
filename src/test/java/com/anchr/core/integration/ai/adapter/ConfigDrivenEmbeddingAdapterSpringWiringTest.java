package com.anchr.core.integration.ai.adapter;

import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConfigDrivenEmbeddingAdapterSpringWiringTest {

    @Test
    void springShouldRequireAndInjectTheCapabilityRepository() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            registerMock(context, ClientCacheManager.class);
            registerMock(context, CapabilityClientFactory.class);
            registerMock(context, CapabilityResolver.class);
            registerMock(context, CapabilityConfigRepository.class);
            context.register(ConfigDrivenEmbeddingAdapter.class);
            context.refresh();

            assertThat(context.getBean(ConfigDrivenEmbeddingAdapter.class))
                    .isNotNull();
        }
    }

    private <T> void registerMock(
            AnnotationConfigApplicationContext context,
            Class<T> type
    ) {
        context.registerBean(type, () -> mock(type));
    }
}
