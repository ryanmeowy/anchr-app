package com.anchr.core.kb.infrastructure.persistence;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AssetMapperXmlTest {

    @Test
    void documentListStatements_shouldApplyAvailabilityFilters() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(AssetMapper.class);
        String resource = "mapper/kb/AssetMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        String namespace = AssetMapper.class.getName() + ".";
        String answerable = sql(configuration, namespace, "ANSWERABLE");
        String processing = sql(configuration, namespace, "PROCESSING");
        String failed = sql(configuration, namespace, "FAILED");

        assertThat(answerable)
                .contains("coalesce(active_index_generation, 0) > 0")
                .contains("index_status = 'SUCCESS'");
        assertThat(processing)
                .contains("coalesce(active_index_generation, 0) = 0")
                .contains("in ('PENDING', 'RUNNING')");
        assertThat(failed)
                .contains("coalesce(active_index_generation, 0) = 0")
                .contains("parse_status = 'FAILED' or index_status = 'FAILED'");
    }

    private String sql(Configuration configuration, String namespace, String availabilityStatus) {
        return configuration.getMappedStatement(namespace + "listActive")
                .getBoundSql(Map.of(
                        "kbId", "kb-1",
                        "keyword", "",
                        "fileType", "",
                        "availabilityStatus", availabilityStatus,
                        "limit", 24,
                        "offset", 0))
                .getSql()
                .replaceAll("\\s+", " ")
                .trim();
    }
}
