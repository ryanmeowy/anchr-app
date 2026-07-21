package com.anchr.core.integration.ai.adapter;

import com.anchr.core.conversation.domain.port.ConversationRewritePort;
import com.anchr.core.conversation.domain.port.ConversationGenerationPort;
import com.anchr.core.conversation.application.model.ConversationModelMessage;
import com.anchr.core.conversation.application.model.ConversationGenerationResult;
import com.anchr.core.conversation.application.model.GenerationOptions;
import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.GenerationClient;
import com.anchr.core.search.domain.port.SearchGenerationPort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenGenerationAdapter implements ConversationRewritePort, ConversationGenerationPort, SearchGenerationPort {

    private final ClientCacheManager cacheManager;
    private final CapabilityClientFactory clientFactory;
    private final CapabilityResolver configResolver;
    private final ObjectMapper objectMapper;

    @Override
    public String generateText(String prompt) {
        ClientCacheManager.ResolvedClient resolved = cacheManager.getOrBuild(
                CapabilityResolver.SLOT_GENERATION, this::resolve);
        GenerationClient client = (GenerationClient) resolved.client();
        return client.generate(resolved.config().getModelName(), Map.of(), prompt).content();
    }

    @Override
    public String generate(List<ConversationModelMessage> messages, GenerationOptions options) {
        return generateWithUsage(messages, options).content();
    }

    @Override
    public ConversationGenerationResult generateWithUsage(List<ConversationModelMessage> messages,
                                                            GenerationOptions options) {
        return generateInternal(messages, options, null);
    }

    @Override
    public ConversationGenerationResult generateStream(List<ConversationModelMessage> messages,
                                                        GenerationOptions options,
                                                        Consumer<String> onDelta) {
        return generateInternal(messages, options, onDelta);
    }

    private ConversationGenerationResult generateInternal(List<ConversationModelMessage> messages,
                                                           GenerationOptions options,
                                                           Consumer<String> onDelta) {
        ClientCacheManager.ResolvedClient resolved = cacheManager.getOrBuild(
                CapabilityResolver.SLOT_GENERATION, this::resolve);
        GenerationClient client = (GenerationClient) resolved.client();
        Map<String, Object> extraConfig = parseExtraConfig(resolved.config().getExtraConfig());
        if (options != null) {
            if (options.temperature() != null) {
                extraConfig.put("temperature", options.temperature());
            }
            if (options.maxTokens() != null) {
                extraConfig.put("max_tokens", options.maxTokens());
            }
        }
        List<Map<String, String>> mappedMessages = messages == null ? List.of() : messages.stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList();
        Duration timeout = options == null || options.timeout() == null
                ? Duration.ofSeconds(30) : options.timeout();
        GenerationClient.GenerationResult result = onDelta == null
                ? client.generate(resolved.config().getModelName(), mappedMessages, extraConfig, timeout)
                : client.generateStream(resolved.config().getModelName(), mappedMessages, extraConfig, timeout, onDelta);
        return new ConversationGenerationResult(
                result.content(), result.promptTokens(), result.completionTokens());
    }

    private Map<String, Object> parseExtraConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(raw, new TypeReference<>() {
            }));
        } catch (Exception e) {
            log.warn("Failed to parse generation extra config: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private ClientCacheManager.ResolvedClient resolve() {
        CapabilityConfig config = configResolver.activeForSlot(CapabilityResolver.SLOT_GENERATION)
                .orElseThrow(() -> new IllegalStateException(
                        "Generation is not configured"));
        return new ClientCacheManager.ResolvedClient(clientFactory.build(config), config);
    }
}
