package com.anchr.core.integration.ai.adapter;

import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort;
import com.anchr.core.ingestion.domain.port.IngestionEmbeddingPort.ServingEmbeddingSession;
import com.anchr.core.integration.ai.client.CapabilityClientFactory;
import com.anchr.core.integration.ai.client.CapabilityResolver;
import com.anchr.core.integration.ai.client.ClientCacheManager;
import com.anchr.core.integration.ai.client.EmbeddingClient;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.domain.repository.EmbeddingDeploymentRepository;
import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private CapabilityConfigRepository capabilityConfigRepository;
    private EmbeddingDeploymentRepository embeddingDeploymentRepository;

    @Autowired(required = false)
    void setCapabilityConfigRepository(CapabilityConfigRepository repository) {
        this.capabilityConfigRepository = repository;
    }

    @Autowired(required = false)
    void setEmbeddingDeploymentRepository(EmbeddingDeploymentRepository repository) {
        this.embeddingDeploymentRepository = repository;
    }

    public List<Float> embed(String source, String sourceType) {
        if (embeddingDeploymentRepository == null
                && capabilityConfigRepository == null) {
            return embed(resolveActiveClient(), source, sourceType);
        }
        EmbeddingProfile serving = servingProfile().orElse(null);
        return serving == null
                ? embed(resolveActiveClient(), source, sourceType)
                : openSession(serving).embed(source, sourceType);
    }

    @Override
    public EmbeddingSession openSession(EmbeddingProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Embedding profile is required");
        }
        ClientCacheManager.ResolvedClient resolved = profileClients.computeIfAbsent(
                profile.fingerprint(), ignored -> resolveExactClient(profile));
        return (source, sourceType) -> embed(resolved, source, sourceType);
    }

    private ClientCacheManager.ResolvedClient resolveExactClient(EmbeddingProfile profile) {
        if (capabilityConfigRepository == null) {
            ClientCacheManager.ResolvedClient active = resolveActiveClient();
            requireMatchingProfile(active.config(), profile);
            return active;
        }
        CapabilityConfig config = profile.configId() == null
                ? findByFingerprint(profile)
                : capabilityConfigRepository.findById(profile.configId()).orElse(null);
        if (config == null) {
            throw new IllegalStateException(
                    "Embedding config is unavailable for profile " + profile.fingerprint());
        }
        requireMatchingProfile(config, profile);
        return new ClientCacheManager.ResolvedClient(clientFactory.build(config), config);
    }

    private CapabilityConfig findByFingerprint(EmbeddingProfile profile) {
        return java.util.stream.Stream.concat(
                        capabilityConfigRepository.findAllByCapability("EMBEDDING").stream(),
                        capabilityConfigRepository.findAllByCapability("MULTI_EMBEDDING").stream())
                .filter(config -> CapabilityEmbeddingProfileProvider.createProfile(config)
                        .map(candidate -> candidate.fingerprint().equals(profile.fingerprint()))
                        .orElse(false))
                .findFirst()
                .orElse(null);
    }

    private void requireMatchingProfile(CapabilityConfig config, EmbeddingProfile expected) {
        EmbeddingProfile actual = CapabilityEmbeddingProfileProvider.createProfile(config)
                .orElseThrow(() -> new IllegalStateException(
                        "Embedding configuration has no valid profile"));
        if (!actual.fingerprint().equals(expected.fingerprint())) {
            throw new IllegalStateException(
                    "Embedding configuration fingerprint does not match physical index metadata");
        }
    }

    private Optional<EmbeddingProfile> servingProfile() {
        if (embeddingDeploymentRepository != null) {
            Optional<EmbeddingProfile> deployed = embeddingDeploymentRepository.find()
                    .map(deployment -> deployment.servingProfile());
            if (deployed.isPresent()) {
                return deployed;
            }
        }
        if (configResolver == null) {
            return CapabilityEmbeddingProfileProvider.createProfile(
                    resolveActiveClient().config());
        }
        return configResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING)
                .flatMap(CapabilityEmbeddingProfileProvider::createProfile);
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

    @Override
    public boolean isMulti() {
        EmbeddingProfile profile = servingProfile()
                .orElseThrow(() -> new IllegalStateException("Embedding is not configured"));
        return "MULTI_EMBEDDING".equals(profile.capability());
    }

    @Override
    public ServingEmbeddingSession openServingSession() {
        EmbeddingProfile profile = servingProfile()
                .orElseThrow(() -> new IllegalStateException("Embedding is not configured"));
        EmbeddingSession session = openSession(profile);
        return new ServingEmbeddingSession(
                profile,
                "MULTI_EMBEDDING".equals(profile.capability()),
                session::embed);
    }

    private ClientCacheManager.ResolvedClient resolve() {
        CapabilityConfig config = configResolver.activeForSlot(CapabilityResolver.SLOT_EMBEDDING)
                .orElseThrow(() -> new IllegalStateException("Embedding is not configured"));
        return new ClientCacheManager.ResolvedClient(clientFactory.build(config), config);
    }
}
