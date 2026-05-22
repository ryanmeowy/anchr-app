package com.anchr.core.settings.application.impl;

import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import com.anchr.core.settings.application.provider.ProviderIdentity;
import com.anchr.core.settings.config.ProviderConnectionTestProperties;
import com.anchr.core.settings.domain.model.ProviderType;
import com.anchr.core.settings.interfaces.rest.dto.ProviderConnectionTestResultDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConnectionTestServiceImplTest {

    @Test
    void test_shouldReturnReadableFailureWhenInputMissing() {
        ProviderConnectionTestProperties properties = new ProviderConnectionTestProperties();
        ProviderConnectionTestServiceImpl service = new ProviderConnectionTestServiceImpl(
                new ProviderRuntimeRegistry(List.of(new GenerationProvider())), properties);

        ProviderConnectionTestResultDTO result = service.test(ProviderType.GENERATION, "test");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo("TEST_INPUT_MISSING");
        assertThat(result.getMessage()).contains("Generation test prompt");
    }

    @Test
    void test_shouldCallProviderWhenInputConfigured() {
        ProviderConnectionTestProperties properties = new ProviderConnectionTestProperties();
        properties.setGenerationPrompt("ping");
        GenerationProvider provider = new GenerationProvider();
        ProviderConnectionTestServiceImpl service = new ProviderConnectionTestServiceImpl(
                new ProviderRuntimeRegistry(List.of(provider)), properties);

        ProviderConnectionTestResultDTO result = service.test(ProviderType.GENERATION, "test");

        assertThat(result.isSuccess()).isTrue();
        assertThat(provider.called).isTrue();
    }

    private static class GenerationProvider implements ProviderIdentity, ConversationRewritePort {
        private boolean called;

        @Override
        public ProviderType providerType() {
            return ProviderType.GENERATION;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public String generateText(String prompt) {
            called = true;
            return "pong";
        }
    }
}
