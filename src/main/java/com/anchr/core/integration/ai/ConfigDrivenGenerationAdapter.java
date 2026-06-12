package com.anchr.core.integration.ai;

import com.anchr.core.common.util.AesUtil;
import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenGenerationAdapter implements ConversationRewritePort {

    private final CapabilityConfigRepository configRepository;
    private final AesUtil aesUtil;

    @Override
    public String generateText(String prompt) {
        CapabilityConfig config = loadConfig();
        GenerationClient client = new GenerationClient(config.getBaseUrl(), decrypt(config.getApiKeyEnc()));
        return client.generate(config.getModelName(), Map.of(), prompt).content();
    }

    private CapabilityConfig loadConfig() {
        return configRepository.findByCapability("GENERATION")
                .orElseThrow(() -> new IllegalStateException(
                        "Generation is not configured. Save config via PATCH /api/v1/settings/generation."));
    }

    private String decrypt(String encrypted) {
        try { return aesUtil.decrypt(encrypted); }
        catch (Exception e) { throw new IllegalStateException("Failed to decrypt generation apiKey.", e); }
    }
}
