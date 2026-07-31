package com.anchr.core.search.application.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.json.JsonData;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager;
import com.anchr.core.search.infrastructure.persistence.es.SegmentIndexAliasManager.AliasTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
final class SegmentIndexTopologyInspector {
    private static final String META_PROFILE_VERSION = "embeddingProfileVersion";
    private static final String META_PROFILE_FINGERPRINT = "embeddingProfileFingerprint";
    private static final String META_MODEL = "embeddingModel";
    private static final String META_DIMENSION = "embeddingDimension";

    private final ElasticsearchClient esClient;
    private final SegmentIndexAliasManager aliasManager;

    IndexInspection inspect(boolean forceMappingLoad, String currentReadIndex) {
        AliasTopology topology = aliasManager.inspect();
        boolean loadMapping = topology.readable()
                && (forceMappingLoad || !Objects.equals(topology.readIndex(), currentReadIndex));
        MappingProfile mappingProfile = loadMapping
                ? inspectMappingProfile(topology.readIndex())
                : MappingProfile.notLoaded();
        return new IndexInspection(
                topology.readable(),
                topology.writable(),
                topology.readIndex(),
                topology.error(),
                mappingProfile);
    }

    private MappingProfile inspectMappingProfile(String indexName) {
        if (!StringUtils.hasText(indexName)) {
            return MappingProfile.empty();
        }
        try {
            Map<String, IndexMappingRecord> mappings = esClient.indices()
                    .getMapping(m -> m.index(indexName)).result();
            return mappings.values().stream()
                    .findFirst()
                    .map(record -> toMappingProfile(indexName, record))
                    .orElseGet(MappingProfile::empty);
        } catch (Exception e) {
            log.warn("Failed to query index status via alias [{}]: {}",
                    indexName, e.getMessage());
            return MappingProfile.empty();
        }
    }

    private MappingProfile toMappingProfile(
            String indexName,
            IndexMappingRecord record
    ) {
        if (record.mappings() == null) {
            return MappingProfile.empty();
        }
        Integer actualDim = null;
        var embeddingProp = record.mappings().properties().get("embedding");
        if (embeddingProp != null && embeddingProp.isDenseVector()) {
            actualDim = embeddingProp.denseVector().dims();
        }

        Map<String, JsonData> metadata = record.mappings().meta();
        String actualModel = readMetadataString(metadata, META_MODEL);
        String actualProfileFingerprint =
                readMetadataString(metadata, META_PROFILE_FINGERPRINT);
        Integer metadataVersion = readMetadataInteger(metadata, META_PROFILE_VERSION);
        Integer metadataDimension = readMetadataInteger(metadata, META_DIMENSION);
        if (!Objects.equals(metadataVersion, 1)
                || !Objects.equals(metadataDimension, actualDim)) {
            log.warn("Index [{}] has invalid embedding profile metadata", indexName);
            actualProfileFingerprint = null;
        }
        return new MappingProfile(
                true, actualDim, actualModel, actualProfileFingerprint);
    }

    static String readMetadataString(Map<String, JsonData> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        return metadata.get(key).to(String.class);
    }

    static Integer readMetadataInteger(Map<String, JsonData> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return null;
        }
        return metadata.get(key).to(Integer.class);
    }

    record IndexInspection(
            boolean readable,
            boolean writable,
            String readIndex,
            String error,
            MappingProfile mappingProfile
    ) {
    }

    record MappingProfile(
            boolean loaded,
            Integer actualDim,
            String actualModel,
            String actualProfileFingerprint
    ) {
        static MappingProfile notLoaded() {
            return new MappingProfile(false, null, null, null);
        }

        static MappingProfile empty() {
            return new MappingProfile(true, null, null, null);
        }
    }
}
