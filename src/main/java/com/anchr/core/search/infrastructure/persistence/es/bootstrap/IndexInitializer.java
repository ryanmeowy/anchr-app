package com.anchr.core.search.infrastructure.persistence.es.bootstrap;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.anchr.core.common.config.VectorConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

@Slf4j
@RequiredArgsConstructor
public class IndexInitializer {

    private final ElasticsearchClient esClient;
    private final VectorConfig vectorConfig;

    private static final String SETTINGS_PATH = "es-settings.json";
    private static final String MAPPING_PATH = "es-mapping.json";

    @PostConstruct
    public void init() {
        String physicalIndexName = vectorConfig.getPhysicalIndexName();
        try {
            BooleanResponse exists = esClient.indices().exists(e -> e.index(physicalIndexName));
            if (!exists.value()) {
                log.info("Starting index initialization [{}], loading configuration file...", physicalIndexName);

                InputStream settingsStream = new ClassPathResource(SETTINGS_PATH).getInputStream();

                String mappingJson = loadAndProcessMapping(vectorConfig.getDimension());

                esClient.indices().create(c -> c
                        .index(physicalIndexName)
                        .settings(IndexSettings.of(s -> s.withJson(settingsStream)))
                        .mappings(TypeMapping.of(m -> m.withJson(new StringReader(mappingJson))))
                );

                log.info("Index [{}] created successfully!", physicalIndexName);
            } else {
                log.info("Index [{}] already exists, skip creating", physicalIndexName);
            }
            ensureAliases(physicalIndexName);

        } catch (Exception e) {
            log.error("Index initialization failed", e);
            throw new RuntimeException("ES Index Init Failed");
        }
    }

    /**
     * Read the mapping file
     */
    private String loadAndProcessMapping(int dims) throws IOException {
        ClassPathResource resource = new ClassPathResource(MAPPING_PATH);
        String json = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        return json.replace("\"@DIMS@\"", String.valueOf(dims));
    }

    private void ensureAliases(String physicalIndexName) throws IOException {
        bindAliasIfNeeded(vectorConfig.getReadAlias(), physicalIndexName, false);
        bindAliasIfNeeded(vectorConfig.getWriteAlias(), physicalIndexName, true);
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
        log.info("Alias [{}] bound to index [{}], writeAlias={}", alias, physicalIndexName, writeAlias);
    }
}
