package com.anchr.core.search.application.impl;

import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedSearchTopChunkMappingTest {

    @Test
    void toTopChunk_shouldKeepOriginalContentAndDocumentPosition() {
        UnifiedSearchServiceImpl service = new UnifiedSearchServiceImpl(
                null, null, null, null, null, null, null);
        SearchResultDTO segment = SearchResultDTO.builder()
                .segmentId("seg-1")
                .title("2.1 Retrieval")
                .content("full original content")
                .snippet("short snippet")
                .pageNo(3)
                .anchor(SearchResultDTO.Anchor.builder().pageNo(3).chunkOrder(12).build())
                .build();

        SearchResultDTO.TopChunk topChunk = ReflectionTestUtils.invokeMethod(service, "toTopChunk", segment);

        assertThat(topChunk).isNotNull();
        assertThat(topChunk.getTitle()).isEqualTo("2.1 Retrieval");
        assertThat(topChunk.getContent()).isEqualTo("full original content");
        assertThat(topChunk.getAnchor().getChunkOrder()).isEqualTo(12);
    }
}
