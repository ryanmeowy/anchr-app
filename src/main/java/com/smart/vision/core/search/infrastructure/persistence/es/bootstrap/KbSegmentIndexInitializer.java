package com.smart.vision.core.search.infrastructure.persistence.es.bootstrap;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.smart.vision.core.common.config.KbSegmentConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Initializes kb_segment index and aliases for text+image unified retrieval.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbSegmentIndexInitializer {

    private static final String SETTINGS_PATH = "es-settings.json";
    private static final String MAPPING_PATH = "es-kb-segment-mapping.json";

    private final ElasticsearchClient esClient;
    private final KbSegmentConfig kbSegmentConfig;

    @PostConstruct
    public void init() {
        String physicalIndexName = kbSegmentConfig.getPhysicalIndexName();
        try {
            BooleanResponse exists = esClient.indices().exists(e -> e.index(physicalIndexName));
            if (!exists.value()) {
                log.info("Starting kb_segment index initialization [{}]", physicalIndexName);

                InputStream settingsStream = new ClassPathResource(SETTINGS_PATH).getInputStream();
                String mappingJson = loadAndProcessMapping(resolveDimension());

                esClient.indices().create(c -> c
                        .index(physicalIndexName)
                        .settings(IndexSettings.of(s -> s.withJson(settingsStream)))
                        .mappings(TypeMapping.of(m -> m.withJson(new StringReader(mappingJson))))
                );

                log.info("kb_segment index [{}] created successfully", physicalIndexName);
            } else {
                log.info("kb_segment index [{}] already exists, skip creating", physicalIndexName);
            }
            ensureAliases(physicalIndexName);
        } catch (Exception e) {
            log.error("kb_segment index initialization failed", e);
            throw new RuntimeException("KB Segment ES Index Init Failed");
        }
    }

    private int resolveDimension() {
        Integer resolved = kbSegmentConfig.getDimension();
        if (resolved != null && resolved > 0) {
            return resolved;
        }
        throw new IllegalStateException("kb segment vector dimension is missing");
    }

    private String loadAndProcessMapping(int dims) throws IOException {
        ClassPathResource resource = new ClassPathResource(MAPPING_PATH);
        String json = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        return json.replace("\"@DIMS@\"", String.valueOf(dims));
    }

    private void ensureAliases(String physicalIndexName) throws IOException {
        bindAliasIfNeeded(kbSegmentConfig.getReadAlias(), physicalIndexName, false);
        bindAliasIfNeeded(kbSegmentConfig.getWriteAlias(), physicalIndexName, true);
    }

    private void bindAliasIfNeeded(String alias, String physicalIndexName, boolean writeAlias) throws IOException {
        if (alias == null || alias.isBlank()) {
            return;
        }
        boolean aliasExists = esClient.indices().existsAlias(e -> e.name(alias)).value();
        if (aliasExists) {
            log.info("Alias [{}] already exists, skip binding", alias);
            return;
        }

        esClient.indices().updateAliases(u -> u
                .actions(a -> a.add(add -> add
                        .index(physicalIndexName)
                        .alias(alias)
                        .isWriteIndex(writeAlias ? Boolean.TRUE : null)
                )));
        log.info("Alias [{}] bound to [{}], writeAlias={}", alias, physicalIndexName, writeAlias);
    }
}
