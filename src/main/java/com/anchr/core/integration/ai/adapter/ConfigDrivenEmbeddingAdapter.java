package com.anchr.core.integration.ai.adapter;

import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.EmbeddingClient;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI-compatible embedding adapter backed by capability_config.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ConfigDrivenEmbeddingAdapter implements SearchEmbeddingPort, IngestionEmbeddingPort {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ClientCacheManager cacheManager;
    private final CapabilityClientFactory clientFactory;
    private final CapabilityResolver configResolver;
    private final Map<String, ClientCacheManager.ResolvedClient> profileClients =
            new ConcurrentHashMap<>();
    private final CapabilityConfigRepository capabilityConfigRepository;

    public List<Float> embed(String source, String sourceType) {
        return embed(resolveActiveClient(), source, sourceType);
    }

    @Override
    public SearchEmbeddingPort.EmbeddingSession openSession(EmbeddingProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Embedding profile is required");
        }
        ClientCacheManager.ResolvedClient resolved = profileClients.computeIfAbsent(
                profile.fingerprint(), ignored -> resolveProfileClient(profile));
        return new SearchEmbeddingPort.EmbeddingSession() {
            @Override
            public List<Float> embed(String source, String sourceType) {
                return ConfigDrivenEmbeddingAdapter.this.embed(
                        resolved, source, sourceType);
            }

            @Override
            public List<List<Float>> embedBatch(List<EmbeddingInput> inputs) {
                return ConfigDrivenEmbeddingAdapter.this.embedBatch(resolved, inputs);
            }
        };
    }

    private ClientCacheManager.ResolvedClient resolveProfileClient(EmbeddingProfile profile) {
        if (profile.configId() == null) {
            ClientCacheManager.ResolvedClient active = resolveActiveClient();
            requireMatchingProfile(active.config(), profile);
            return active;
        }
        CapabilityConfig config = capabilityConfigRepository.findById(profile.configId())
                .orElseThrow(() -> new IllegalStateException(
                        "Embedding config is unavailable: " + profile.configId()));
        requireMatchingProfile(config, profile);
        return new ClientCacheManager.ResolvedClient(clientFactory.build(config), config);
    }

    private void requireMatchingProfile(CapabilityConfig config, EmbeddingProfile expected) {
        EmbeddingProfile actual = CapabilityEmbeddingProfileProvider.createProfile(config)
                .orElseThrow(() -> new IllegalStateException(
                        "Embedding configuration has no valid profile"));
        if (!actual.fingerprint().equals(expected.fingerprint())) {
            throw new IllegalStateException(
                    "Embedding configuration changed before rebuild started");
        }
    }

    private ClientCacheManager.ResolvedClient resolveActiveClient() {
        return cacheManager.getOrBuild(CapabilityResolver.SLOT_EMBEDDING, this::resolve);
    }

    private List<Float> embed(
            ClientCacheManager.ResolvedClient resolved,
            String source,
            String sourceType
    ) {
        CapabilityConfig config = resolved.config();
        EmbeddingClient client = (EmbeddingClient) resolved.client();
        boolean isMulti = "MULTI_EMBEDDING".equals(config.getCapability());

        Map<String, Object> extraMap = new HashMap<>();
        if (StringUtils.hasText(config.getExtraConfig())) {
            try {
                extraMap = objectMapper.readValue(config.getExtraConfig(), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        EmbeddingClient.EmbedContext context = null;
        if ("text".equals(sourceType)) {
            if (isMulti) {
                Map<String, Object> map = new HashMap<>();
                map.put("text", source);
                Map<String, Object> contentMap = new HashMap<>();
                contentMap.put("contents", Lists.newArrayList(map));
                context = EmbeddingClient.EmbedContext
                        .builder()
                        .modelName(config.getModelName())
                        .extraConfig(extraMap)
                        .contentMap(contentMap)
                        .build();
            } else {
                context = EmbeddingClient.EmbedContext
                        .builder()
                        .modelName(config.getModelName())
                        .extraConfig(extraMap)
                        .texts(List.of(source))
                        .build();
            }
        }

        if ("image".equals(sourceType)) {
            if (isMulti) {
                Map<String, Object> map = new HashMap<>();
                map.put("image", source);
                Map<String, Object> contentMap = new HashMap<>();
                contentMap.put("contents", Lists.newArrayList(map));
                context = EmbeddingClient.EmbedContext
                        .builder()
                        .modelName(config.getModelName())
                        .extraConfig(extraMap)
                        .contentMap(contentMap)
                        .build();
            }
        }
        if (null == context) {
            return Lists.newArrayList();
        }
        return client.embed(context).vector();
    }

    private List<List<Float>> embedBatch(
            ClientCacheManager.ResolvedClient resolved,
            List<EmbeddingInput> inputs
    ) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        CapabilityConfig config = resolved.config();
        EmbeddingClient client = (EmbeddingClient) resolved.client();
        boolean isMulti = "MULTI_EMBEDDING".equals(config.getCapability());
        Map<String, Object> extraMap = extraConfig(config);
        EmbeddingClient.EmbedContext context;
        if (isMulti) {
            List<Map<String, Object>> contents = new java.util.ArrayList<>(inputs.size());
            for (EmbeddingInput input : inputs) {
                Map<String, Object> content = new HashMap<>();
                content.put(input.sourceType(), input.source());
                contents.add(content);
            }
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("contents", contents);
            context = EmbeddingClient.EmbedContext.builder()
                    .modelName(config.getModelName())
                    .extraConfig(extraMap)
                    .contentMap(contentMap)
                    .build();
        } else {
            boolean unsupported = inputs.stream()
                    .anyMatch(input -> !"text".equals(input.sourceType()));
            if (unsupported) {
                return inputs.stream()
                        .map(input -> embed(
                                resolved, input.source(), input.sourceType()))
                        .toList();
            }
            context = EmbeddingClient.EmbedContext.builder()
                    .modelName(config.getModelName())
                    .extraConfig(extraMap)
                    .texts(inputs.stream().map(EmbeddingInput::source).toList())
                    .build();
        }
        List<List<Float>> vectors = client.embedMany(context).stream()
                .map(EmbeddingClient.EmbeddingResult::vector)
                .toList();
        if (vectors.size() != inputs.size()) {
            throw new IllegalStateException(
                    "Embedding batch response size mismatch: expected "
                            + inputs.size() + ", actual " + vectors.size());
        }
        return vectors;
    }

    private Map<String, Object> extraConfig(CapabilityConfig config) {
        if (!StringUtils.hasText(config.getExtraConfig())) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(config.getExtraConfig(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isMulti() {
        CapabilityConfig config = configResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING)
                .orElseThrow(() -> new IllegalStateException("Embedding is not configured"));
        return "MULTI_EMBEDDING".equals(config.getCapability());
    }

    @Override
    public IngestionEmbeddingPort.EmbeddingSession openSession() {
        ClientCacheManager.ResolvedClient resolved = resolveActiveClient();
        EmbeddingProfile profile = CapabilityEmbeddingProfileProvider
                .createProfile(resolved.config())
                .orElseThrow(() -> new IllegalStateException(
                        "Embedding configuration has no valid profile"));
        return new IngestionEmbeddingPort.EmbeddingSession() {
            @Override
            public List<Float> embed(String source, String sourceType) {
                return ConfigDrivenEmbeddingAdapter.this.embed(
                        resolved, source, sourceType);
            }

            @Override
            public boolean isMulti() {
                return "MULTI_EMBEDDING".equals(resolved.config().getCapability());
            }

            @Override
            public String profileFingerprint() {
                return profile.fingerprint();
            }
        };
    }

    private ClientCacheManager.ResolvedClient resolve() {
        CapabilityConfig config = configResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING)
                .orElseThrow(() -> new IllegalStateException("Embedding is not configured"));
        return new ClientCacheManager.ResolvedClient(clientFactory.build(config), config);
    }
}
