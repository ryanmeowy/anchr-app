package com.anchr.core.kb.application.impl;

import com.anchr.core.kb.domain.model.Asset;
import com.anchr.core.kb.domain.model.KnowledgeBase;
import com.anchr.core.kb.domain.model.KnowledgeBaseStatus;
import com.anchr.core.kb.domain.repository.AssetRepository;
import com.anchr.core.kb.domain.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class KnowledgeContentQueryApiImplTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
    private final AssetRepository assetRepository = mock(AssetRepository.class);
    private final KnowledgeContentQueryApiImpl api =
            new KnowledgeContentQueryApiImpl(knowledgeBaseRepository, assetRepository);

    @Test
    void exposesOnlyGenericActiveContentFacts() {
        KnowledgeBase kb = KnowledgeBase.builder()
                .id("kb-1").name("Knowledge").status(KnowledgeBaseStatus.ACTIVE).build();
        Asset asset = Asset.builder()
                .id("asset-1").kbId("kb-1").fileName("guide.pdf")
                .objectKey("docs/guide.pdf").previewObjectKey("previews/guide.pdf")
                .activeIndexGeneration(4L).segmentCount(12).build();
        when(knowledgeBaseRepository.searchKbs(null, "ACTIVE", null, null, 100, 0))
                .thenReturn(List.of(kb));
        when(knowledgeBaseRepository.findActiveById("kb-1")).thenReturn(Optional.of(kb));
        when(assetRepository.findActiveById("kb-1", "asset-1")).thenReturn(Optional.of(asset));
        when(assetRepository.findActiveIndexGenerations(List.of("asset-1", "missing")))
                .thenReturn(Map.of("asset-1", 4L));
        when(assetRepository.listActive("kb-1", "guide", null, 100, 0))
                .thenReturn(List.of(asset));

        assertThat(api.listActiveKnowledgeBases()).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo("kb-1");
            assertThat(summary.status()).isEqualTo("ACTIVE");
        });
        assertThat(api.findActiveKnowledgeBase("kb-1")).get().extracting("name").isEqualTo("Knowledge");
        assertThat(api.findActiveDocument("kb-1", "asset-1")).get().satisfies(summary -> {
            assertThat(summary.fileName()).isEqualTo("guide.pdf");
            assertThat(summary.activeIndexGeneration()).isEqualTo(4L);
            assertThat(summary.segmentCount()).isEqualTo(12);
        });
        assertThat(api.searchActiveDocuments(" kb-1 ", " guide ", 500))
                .singleElement().satisfies(summary -> {
                    assertThat(summary.id()).isEqualTo("asset-1");
                    assertThat(summary.segmentCount()).isEqualTo(12);
                });
        verify(assetRepository).listActive("kb-1", "guide", null, 100, 0);
        assertThat(api.findActiveIndexGenerations(List.of("asset-1", "missing")))
                .containsExactlyEntriesOf(Map.of("asset-1", 4L));
        assertThat(api.findActiveDocument("kb-1", "missing")).isEmpty();
    }
}
