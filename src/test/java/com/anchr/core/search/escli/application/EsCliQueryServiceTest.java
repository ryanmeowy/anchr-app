package com.anchr.core.search.escli.application;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.escli.domain.EsCliAccessControl;
import com.anchr.core.search.escli.interfaces.rest.dto.EsIndexMappingDTO;
import com.anchr.core.search.escli.interfaces.rest.dto.EsDocSearchRequestDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsCliQueryServiceTest {

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private ElasticsearchIndicesClient indicesClient;
    @Mock
    private EsCliAccessControl accessControl;

    private EsCliQueryService service;

    @BeforeEach
    void setUp() {
        service = new EsCliQueryService(esClient, new ObjectMapper(), accessControl, new SimpleMeterRegistry());
    }

    @Test
    void listIndices_shouldRejectInvalidPatternBeforeEsCall() {
        assertThatThrownBy(() -> service.listIndices("bad pattern", null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid index pattern");
    }

    @Test
    void getIndexStats_shouldRejectInvalidIndexBeforeEsCall() {
        assertThatThrownBy(() -> service.getIndexStats("smart_*"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid index name");
    }

    @Test
    void searchDocuments_shouldRejectUnsupportedSourceField() {
        EsDocSearchRequestDTO request = new EsDocSearchRequestDTO();
        request.setQuery("fileName:cat");
        request.setSourceIncludes(java.util.List.of("imageEmbedding"));

        assertThatThrownBy(() -> service.searchDocuments("smart_gallery_local_read", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("sourceIncludes contains unsupported fields");
    }

    @Test
    void searchDocuments_shouldRejectUnsafeQueryChars() {
        EsDocSearchRequestDTO request = new EsDocSearchRequestDTO();
        request.setQuery("fileName:cat;DROP TABLE");

        assertThatThrownBy(() -> service.searchDocuments("smart_gallery_local_read", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("query contains unsupported characters");
    }

    @Test
    void getIndexMapping_shouldReturnMappingsPayload() throws Exception {
        when(esClient.indices()).thenReturn(indicesClient);
        TypeMapping typeMapping = TypeMapping.of(m -> m.properties("fileName", p -> p.text(t -> t)));
        IndexMappingRecord record = IndexMappingRecord.of(r -> r.mappings(typeMapping));
        GetMappingResponse response = GetMappingResponse.of(r -> r.result("smart_gallery_local_read", record));
        when(indicesClient.getMapping(any(Function.class))).thenReturn(response);

        EsIndexMappingDTO result = service.getIndexMapping("smart_gallery_local_read");

        assertThat(result.index()).isEqualTo("smart_gallery_local_read");
        assertThat(result.mapping()).containsKey("properties");
        Map<String, Object> properties = (Map<String, Object>) result.mapping().get("properties");
        assertThat(properties).containsKey("fileName");
    }
}
