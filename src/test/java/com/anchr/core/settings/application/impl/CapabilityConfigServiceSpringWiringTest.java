package com.anchr.core.settings.application.impl;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.settings.application.acl.CapabilityRetrievalAcl;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CapabilityConfigServiceSpringWiringTest {

    @Test
    void springShouldRequireAndInjectTheRetrievalAcl() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            registerMock(context, CapabilityConfigRepository.class);
            registerMock(context, AesUtil.class);
            registerMock(context, IdGen.class);
            registerMock(context, CapabilityClientFactory.class);
            registerMock(context, CapabilityResolver.class);
            registerMock(context, ClientCacheManager.class);
            registerMock(context, CapabilityRetrievalAcl.class);
            context.register(CapabilityConfigServiceImpl.class);
            context.refresh();

            assertThat(context.getBean(CapabilityConfigServiceImpl.class))
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
