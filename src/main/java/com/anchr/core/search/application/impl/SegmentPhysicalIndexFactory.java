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
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    private final ElasticsearchClient esClient;

    String newPhysicalIndexName() {
        return INDEX_NAME + "_" + System.currentTimeMillis();
    }

    void create(String physicalIndexName, EmbeddingProfile profile) throws Exception {
        String mappingJson = loadAndProcessMapping(profile.dimension());
        try (InputStream is = new ClassPathResource(SETTINGS_PATH).getInputStream()) {
            Map<String, JsonData> profileMetadata = toMappingMetadata(profile);
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
}
