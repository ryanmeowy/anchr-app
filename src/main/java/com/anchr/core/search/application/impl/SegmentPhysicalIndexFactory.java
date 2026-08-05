package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.json.JsonData;
import com.anchr.core.search.domain.model.EmbeddingProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import static com.anchr.core.common.constant.SegmentIndexConstant.INDEX_NAME;

@Slf4j
@Component
@RequiredArgsConstructor
final class SegmentPhysicalIndexFactory {
    private static final String SETTINGS_PATH = "es-settings.json";
    private static final String MAPPING_PATH = "es-kb-segment-mapping.json";
    private static final String META_PROFILE_VERSION = "embeddingProfileVersion";
    private static final String META_PROFILE_FINGERPRINT = "embeddingProfileFingerprint";
    private static final String META_CAPABILITY = "embeddingCapability";
    private static final String META_MODEL = "embeddingModel";
    private static final String META_DIMENSION = "embeddingDimension";
    private static final String META_CONFIG_ID = "embeddingConfigId";
    private static final String META_REBUILD_TASK_ID = "rebuildTaskId";
    private static final String META_REBUILD_STATE = "rebuildState";
    private static final String META_REBUILD_SOURCE_INDEX = "rebuildSourceIndex";

    private final ElasticsearchClient esClient;

    String newPhysicalIndexName() {
        return INDEX_NAME + "_" + System.currentTimeMillis();
    }

    void create(String physicalIndexName, EmbeddingProfile profile) throws Exception {
        create(physicalIndexName, profile, null, "ACTIVE", null);
    }

    void createRebuildTarget(
            String physicalIndexName,
            EmbeddingProfile profile,
            String taskId,
            String sourceIndex
    ) throws Exception {
        create(physicalIndexName, profile, taskId, "BUILDING", sourceIndex);
    }

    private void create(
            String physicalIndexName,
            EmbeddingProfile profile,
            String taskId,
            String rebuildState,
            String sourceIndex
    ) throws Exception {
        String mappingJson = loadAndProcessMapping(profile.dimension());
        try (InputStream is = new ClassPathResource(SETTINGS_PATH).getInputStream()) {
            Map<String, JsonData> profileMetadata = toMappingMetadata(
                    profile, taskId, rebuildState, sourceIndex);
            esClient.indices().create(c -> c
                    .index(physicalIndexName)
                    .settings(IndexSettings.of(s -> s.withJson(is)))
                    .mappings(TypeMapping.of(m -> m
                            .withJson(new StringReader(mappingJson))
                            .meta(profileMetadata))));
        }
        log.info("Index [{}] created with embedding profile {}, dim={}, model={}",
                physicalIndexName,
                profile.fingerprint(),
                profile.dimension(),
                profile.modelName());
    }

    void markSwitching(
            String physicalIndexName,
            EmbeddingProfile profile,
            String taskId,
            String sourceIndex
    ) throws Exception {
        updateMetadata(
                physicalIndexName, profile, taskId, "SWITCHING", sourceIndex);
    }

    void markActiveBestEffort(
            String physicalIndexName,
            EmbeddingProfile profile,
            String taskId,
            String sourceIndex
    ) {
        try {
            updateMetadata(
                    physicalIndexName, profile, taskId, "ACTIVE", sourceIndex);
        } catch (Exception error) {
            log.warn("Failed to mark rebuilt index [{}] active: {}",
                    physicalIndexName, error.getMessage());
        }
    }

    private void updateMetadata(
            String physicalIndexName,
            EmbeddingProfile profile,
            String taskId,
            String state,
            String sourceIndex
    ) throws Exception {
        esClient.indices().putMapping(mapping -> mapping
                .index(physicalIndexName)
                .meta(toMappingMetadata(profile, taskId, state, sourceIndex)));
    }

    void cleanupAbandonedRebuildTargets(Set<String> protectedIndices) {
        try {
            var mappings = esClient.indices().getMapping(
                    request -> request.index(INDEX_NAME + "_*")).result();
            for (var entry : mappings.entrySet()) {
                if (protectedIndices.contains(entry.getKey())
                        || entry.getValue().mappings() == null) {
                    continue;
                }
                String state = SegmentIndexTopologyInspector.readMetadataString(
                        entry.getValue().mappings().meta(), META_REBUILD_STATE);
                if ("BUILDING".equals(state) || "SWITCHING".equals(state)) {
                    esClient.indices().delete(delete -> delete.index(entry.getKey()));
                    log.info("Deleted abandoned rebuild target [{}]", entry.getKey());
                }
            }
        } catch (Exception error) {
            log.warn("Failed to clean abandoned rebuild targets: {}", error.getMessage());
        }
    }

    private String loadAndProcessMapping(int dims) throws Exception {
        ClassPathResource resource = new ClassPathResource(MAPPING_PATH);
        try (InputStream is = resource.getInputStream()) {
            String json = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
            return json.replace("\"@DIMS@\"", String.valueOf(dims));
        }
    }

    static Map<String, JsonData> toMappingMetadata(EmbeddingProfile profile) {
        return Map.of(
                META_PROFILE_VERSION, JsonData.of(1),
                META_PROFILE_FINGERPRINT, JsonData.of(profile.fingerprint()),
                META_CAPABILITY, JsonData.of(profile.capability()),
                META_MODEL, JsonData.of(profile.modelName()),
                META_DIMENSION, JsonData.of(profile.dimension()));
    }

    private static Map<String, JsonData> toMappingMetadata(
            EmbeddingProfile profile,
            String taskId,
            String rebuildState,
            String sourceIndex
    ) {
        Map<String, JsonData> metadata = new HashMap<>(toMappingMetadata(profile));
        if (profile.configId() != null) {
            metadata.put(META_CONFIG_ID, JsonData.of(profile.configId()));
        }
        if (StringUtils.hasText(taskId)) {
            metadata.put(META_REBUILD_TASK_ID, JsonData.of(taskId));
        }
        if (StringUtils.hasText(rebuildState)) {
            metadata.put(META_REBUILD_STATE, JsonData.of(rebuildState));
        }
        if (StringUtils.hasText(sourceIndex)) {
            metadata.put(META_REBUILD_SOURCE_INDEX, JsonData.of(sourceIndex));
        }
        return Map.copyOf(metadata);
    }
}
