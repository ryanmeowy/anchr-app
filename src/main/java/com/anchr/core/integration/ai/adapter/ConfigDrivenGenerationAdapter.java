package com.anchr.core.integration.ai.adapter;

import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.GenerationClient;
import com.anchr.core.search.domain.port.SearchGenerationPort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenGenerationAdapter implements ConversationRewritePort, SearchGenerationPort {

    private final ClientCacheManager cacheManager;
    private final CapabilityClientFactory clientFactory;
    private final CapabilityResolver configResolver;

    @Override
    public String generateText(String prompt) {
        ClientCacheManager.ResolvedClient resolved = cacheManager.getOrBuild(
                CapabilityResolver.SLOT_GENERATION, this::resolve);
        GenerationClient client = (GenerationClient) resolved.client();
        return client.generate(resolved.config().getModelName(), Map.of(), prompt).content();
    }

    private ClientCacheManager.ResolvedClient resolve() {
        CapabilityConfig config = configResolver.activeForSlot(CapabilityResolver.SLOT_GENERATION)
                .orElseThrow(() -> new IllegalStateException(
                        "Generation is not configured"));
        return new ClientCacheManager.ResolvedClient(clientFactory.build(config), config);
    }
}
