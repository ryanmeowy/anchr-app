package com.anchr.core.ingestion.infrastructure.persistence;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskMapperXmlTest {

    @Test
    void mapperXml_shouldExposeParseAttemptStatements() throws Exception {
        Configuration configuration = loadConfiguration();

        String namespace = IngestionTaskMapper.class.getName() + ".";
        assertThat(configuration.hasStatement(namespace + "prepareParseAttempt")).isTrue();
        assertThat(configuration.hasStatement(namespace + "recordDoclingJob")).isTrue();
        assertThat(configuration.hasStatement(namespace + "resetFailedItem")).isTrue();
        assertThat(configuration.hasStatement(namespace + "resetFailedItems")).isFalse();
    }

    @Test
    void resetFailedItem_shouldBindExplicitNextIdentityAndExpectedAttempt() throws Exception {
        Configuration configuration = loadConfiguration();
        String statement = IngestionTaskMapper.class.getName() + ".resetFailedItem";
        BoundSql boundSql = configuration.getMappedStatement(statement).getBoundSql(Map.of(
                "kbId", "kb-1",
                "taskId", "task-1",
                "itemId", "item-1",
                "expectedParseAttempt", 7,
                "nextParseAttempt", 8,
                "nextDoclingRequestId", "task-1:item-1:8",
                "updatedAt", LocalDateTime.now()));

        assertThat(boundSql.getSql())
                .contains("iti.docling_request_id = ?")
                .contains("iti.parse_attempt = ?")
                .contains("and iti.parse_attempt = ?")
                .doesNotContain("concat(")
                .doesNotContain("parse_attempt + 1");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly(
                        "nextDoclingRequestId",
                        "nextParseAttempt",
                        "updatedAt",
                        "kbId",
                        "taskId",
                        "itemId",
                        "expectedParseAttempt");
    }

    private Configuration loadConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(IngestionTaskMapper.class);
        String resource = "mapper/ingestion/IngestionTaskMapper.xml";
        XMLMapperBuilder builder = new XMLMapperBuilder(
                Resources.getResourceAsStream(resource),
                configuration,
                resource,
                configuration.getSqlFragments());
        builder.parse();
        return configuration;
    }
}
