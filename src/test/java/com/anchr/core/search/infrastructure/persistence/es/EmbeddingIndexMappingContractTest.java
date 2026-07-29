package com.anchr.core.search.infrastructure.persistence.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingIndexMappingContractTest {

    @Test
    void mappingShouldKeepOneDenseVectorNamedEmbedding() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("es-kb-segment-mapping.json")) {
            JsonNode root = mapper.readTree(input);
            JsonNode properties = root.path("properties");
            List<String> denseVectorFields = new ArrayList<>();
            properties.fields().forEachRemaining(entry -> {
                if ("dense_vector".equals(entry.getValue().path("type").asText())) {
                    denseVectorFields.add(entry.getKey());
                }
            });

            assertThat(denseVectorFields).containsExactly("embedding");
            assertThat(properties.has("textEmbedding")).isFalse();
            assertThat(properties.has("imageEmbedding")).isFalse();
        }
    }
}
