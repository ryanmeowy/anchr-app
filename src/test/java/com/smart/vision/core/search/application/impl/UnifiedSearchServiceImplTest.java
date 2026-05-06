package com.smart.vision.core.search.application.impl;

import com.smart.vision.core.search.application.KbQueryEmbeddingService;
import com.smart.vision.core.search.config.AppSearchProperties;
import com.smart.vision.core.search.domain.model.Bbox;
import com.smart.vision.core.search.domain.model.KbSegmentHit;
import com.smart.vision.core.search.domain.port.KbSegmentSearchPort;
import com.smart.vision.core.search.domain.port.SearchRerankPort;
import com.smart.vision.core.search.domain.port.SearchRerankPort.RerankItem;
import com.smart.vision.core.search.infrastructure.persistence.es.document.KbSegmentDocument;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import com.smart.vision.core.search.interfaces.rest.dto.KbSearchResultDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedSearchServiceImplTest {

    @Mock
    private KbSegmentSearchPort kbSegmentSearchPort;
    @Mock
    private KbQueryEmbeddingService kbQueryEmbeddingService;
    @Mock
    private SearchRerankPort searchRerankPort;

    @Test
    void search_shouldMergeTextAndVectorHitsWithUnifiedSchema() {
        UnifiedSearchServiceImpl service = buildService();

        KbSearchQueryDTO query = new KbSearchQueryDTO();
        query.setQuery("mysql");
        query.setTopK(5);
        query.setLimit(3);
        query.setStrategy("KB_RRF");

        when(kbQueryEmbeddingService.embedQuery("mysql")).thenReturn(List.of(0.1f, 0.2f));
        when(kbSegmentSearchPort.textSearch("mysql", 5)).thenReturn(List.of(
                KbSegmentHit.builder()
                        .document(buildDoc("seg-1", "TEXT", "TEXT_CHUNK", "mysql notes", "mysql chunk", null, 2))
                        .rawScore(3.2d)
                        .highlights(Map.of("contentText", "<em>mysql</em> chunk"))
                        .highlightFields(List.of("contentText"))
                        .build()
        ));
        when(kbSegmentSearchPort.vectorSearch(List.of(0.1f, 0.2f), 5)).thenReturn(List.of(
                KbSegmentHit.builder()
                        .document(buildDoc("seg-1", "TEXT", "TEXT_CHUNK", "mysql notes", "mysql chunk", null, 2))
                        .rawScore(1.1d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build(),
                KbSegmentHit.builder()
                        .document(buildDoc("seg-2", "IMAGE", "IMAGE_CAPTION", "diagram", "mysql architecture", null, null))
                        .rawScore(0.9d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build()
        ));

        List<KbSearchResultDTO> results = service.search(query);

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().getSegmentId()).isEqualTo("seg-1");
        assertThat(results.getFirst().getSegmentType()).isEqualTo("TEXT_CHUNK");
        assertThat(results.getFirst().getResultType()).isEqualTo("TEXT_CHUNK");
        assertThat(results.getFirst().getContent()).contains("mysql");
        assertThat(results.getFirst().getSnippet()).contains("mysql");
        assertThat(results.getFirst().getAnchor()).isNotNull();
        assertThat(results.getFirst().getAnchor().getPageNo()).isEqualTo(2);
        assertThat(results.getFirst().getExplain().getMatchedBy().isVector()).isTrue();
        assertThat(results.getFirst().getExplain().getMatchedBy().isContent()).isTrue();
        assertThat(results.getFirst().getExplain().getStrategyEffective()).isEqualTo("KB_RRF");
        assertThat(results.getFirst().getExplain().getTextSignals()).isNotNull();
        assertThat(results.getFirst().getExplain().getTextSignals().isSemantic()).isTrue();
        assertThat(results.getFirst().getExplain().getTextSignals().isKeyword()).isTrue();
        assertThat(results.getFirst().getExplain().getImageSignals()).isNull();
        assertThat(results.get(1).getAssetType()).isEqualTo("IMAGE");
        assertThat(results.get(1).getExplain().getImageSignals()).isNotNull();
        assertThat(results.get(1).getExplain().getImageSignals().isVector()).isTrue();
        assertThat(results.get(1).getExplain().getImageSignals().isCaption()).isTrue();
        assertThat(results.get(1).getExplain().getImageSignals().isTag()).isTrue();
        assertThat(results.get(1).getExplain().getHitSources()).contains("TAG");
        assertThat(results.get(1).getExplain().getTextSignals()).isNull();
    }

    @Test
    void search_shouldBuildTextOnlyExplainSignals() {
        UnifiedSearchServiceImpl service = buildService();

        KbSearchQueryDTO query = new KbSearchQueryDTO();
        query.setQuery("mysql");
        query.setTopK(5);
        query.setLimit(3);

        when(kbQueryEmbeddingService.embedQuery("mysql")).thenReturn(List.of(0.1f, 0.2f));
        when(kbSegmentSearchPort.textSearch("mysql", 5)).thenReturn(List.of(
                KbSegmentHit.builder()
                        .document(buildDoc("seg-t1", "TEXT", "TEXT_CHUNK", "mysql notes", "mysql chunk", null, 3))
                        .rawScore(2.1d)
                        .highlights(Map.of("contentText", "<em>mysql</em> chunk"))
                        .highlightFields(List.of("contentText"))
                        .build()
        ));
        when(kbSegmentSearchPort.vectorSearch(List.of(0.1f, 0.2f), 5)).thenReturn(List.of());

        List<KbSearchResultDTO> results = service.search(query);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getSegmentType()).isEqualTo("TEXT_CHUNK");
        assertThat(results.getFirst().getExplain().getTextSignals()).isNotNull();
        assertThat(results.getFirst().getExplain().getTextSignals().isSemantic()).isFalse();
        assertThat(results.getFirst().getExplain().getTextSignals().isKeyword()).isTrue();
        assertThat(results.getFirst().getExplain().getTextSignals().isPageHit()).isTrue();
        assertThat(results.getFirst().getExplain().getTextSignals().isChunkHit()).isTrue();
        assertThat(results.getFirst().getExplain().getImageSignals()).isNull();
    }

    @Test
    void search_shouldBuildImageOnlyExplainSignals() {
        UnifiedSearchServiceImpl service = buildService();

        KbSearchQueryDTO query = new KbSearchQueryDTO();
        query.setQuery("mysql");
        query.setTopK(5);
        query.setLimit(3);

        when(kbQueryEmbeddingService.embedQuery("mysql")).thenReturn(List.of(0.1f, 0.2f));
        when(kbSegmentSearchPort.textSearch("mysql", 5)).thenReturn(List.of());
        when(kbSegmentSearchPort.vectorSearch(List.of(0.1f, 0.2f), 5)).thenReturn(List.of(
                KbSegmentHit.builder()
                        .document(buildDoc("seg-i1", "IMAGE", "IMAGE_OCR_BLOCK", "diagram", null, "mysql ocr text", null))
                        .rawScore(1.8d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build()
        ));

        List<KbSearchResultDTO> results = service.search(query);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getSegmentType()).isEqualTo("IMAGE_OCR_BLOCK");
        assertThat(results.getFirst().getExplain().getTextSignals()).isNull();
        assertThat(results.getFirst().getExplain().getImageSignals()).isNotNull();
        assertThat(results.getFirst().getExplain().getImageSignals().isVector()).isTrue();
        assertThat(results.getFirst().getExplain().getImageSignals().isOcr()).isTrue();
        assertThat(results.getFirst().getExplain().getImageSignals().isCaption()).isFalse();
        assertThat(results.getFirst().getExplain().getImageSignals().isTag()).isTrue();
        assertThat(results.getFirst().getExplain().getHitSources()).contains("TAG");
        assertThat(results.getFirst().getAnchor().getBbox().getUnit()).isEqualTo("PIXEL");
        assertThat(results.getFirst().getAnchor().getImageWidth()).isEqualTo(1920);
        assertThat(results.getFirst().getAnchor().getImageHeight()).isEqualTo(1080);
    }

    @Test
    void search_shouldApplyRerankWhenRequested() {
        UnifiedSearchServiceImpl service = buildService();

        KbSearchQueryDTO query = new KbSearchQueryDTO();
        query.setQuery("mysql");
        query.setTopK(3);
        query.setLimit(2);
        query.setStrategy("KB_RRF_RERANK");

        when(kbQueryEmbeddingService.embedQuery("mysql")).thenReturn(List.of(0.1f, 0.2f));
        when(kbSegmentSearchPort.textSearch("mysql", 3)).thenReturn(List.of());
        when(kbSegmentSearchPort.vectorSearch(List.of(0.1f, 0.2f), 3)).thenReturn(List.of(
                KbSegmentHit.builder()
                        .document(buildDoc("seg-1", "TEXT", "TEXT_CHUNK", "mysql notes", "mysql chunk", null, 2))
                        .rawScore(1.0d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build(),
                KbSegmentHit.builder()
                        .document(buildDoc("seg-2", "TEXT", "TEXT_CHUNK", "architecture", "mysql design", null, 3))
                        .rawScore(0.9d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build()
        ));
        when(searchRerankPort.rerank(eq("mysql"), anyList(), eq(2))).thenReturn(List.of(
                new RerankItem(1, 0.95d),
                new RerankItem(0, 0.20d)
        ));

        List<KbSearchResultDTO> results = service.search(query);

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().getSegmentId()).isEqualTo("seg-2");
        assertThat(results.getFirst().getExplain().getStrategyEffective()).isEqualTo("KB_RRF_RERANK");
    }

    @Test
    void search_shouldExpandRecallTopKByConfiguredMultiplier() {
        UnifiedSearchServiceImpl service = buildService(4);

        KbSearchQueryDTO query = new KbSearchQueryDTO();
        query.setQuery("mysql");
        query.setTopK(5);
        query.setLimit(3);

        when(kbQueryEmbeddingService.embedQuery("mysql")).thenReturn(List.of(0.1f, 0.2f));
        when(kbSegmentSearchPort.textSearch("mysql", 12)).thenReturn(List.of());
        when(kbSegmentSearchPort.vectorSearch(List.of(0.1f, 0.2f), 12)).thenReturn(List.of());

        service.search(query);

        verify(kbSegmentSearchPort).textSearch("mysql", 12);
        verify(kbSegmentSearchPort).vectorSearch(List.of(0.1f, 0.2f), 12);
    }

    @Test
    void search_shouldAggregateByAssetAndExposeTopChunks() {
        UnifiedSearchServiceImpl service = buildService();

        KbSearchQueryDTO query = new KbSearchQueryDTO();
        query.setQuery("mysql");
        query.setTopK(5);
        query.setLimit(5);

        when(kbQueryEmbeddingService.embedQuery("mysql")).thenReturn(List.of(0.1f, 0.2f));
        when(kbSegmentSearchPort.textSearch("mysql", 5)).thenReturn(List.of());

        KbSegmentDocument imageCaption = buildDoc("seg-a1", "IMAGE", "IMAGE_CAPTION", "db chart", "mysql chart", null, null);
        imageCaption.setAssetId("asset-image-1");
        KbSegmentDocument imageOcr = buildDoc("seg-a2", "IMAGE", "IMAGE_OCR_BLOCK", "db chart", null, "mysql ocr summary text", null);
        imageOcr.setAssetId("asset-image-1");

        when(kbSegmentSearchPort.vectorSearch(List.of(0.1f, 0.2f), 5)).thenReturn(List.of(
                KbSegmentHit.builder()
                        .document(imageCaption)
                        .rawScore(1.8d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build(),
                KbSegmentHit.builder()
                        .document(imageOcr)
                        .rawScore(1.6d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build()
        ));

        List<KbSearchResultDTO> results = service.search(query);

        assertThat(results).hasSize(1);
        KbSearchResultDTO aggregated = results.getFirst();
        assertThat(aggregated.getAssetId()).isEqualTo("asset-image-1");
        assertThat(aggregated.getTotalHits()).isEqualTo(2);
        assertThat(aggregated.getTopChunks()).hasSize(2);
        assertThat(aggregated.getTopChunks().getFirst().getSegmentId()).isEqualTo("seg-a1");
        assertThat(aggregated.getTopChunks().get(1).getSegmentId()).isEqualTo("seg-a2");
        assertThat(aggregated.getThumbnail()).isEqualTo("oss://seg-a1");
        assertThat(aggregated.getOcrSummary()).contains("mysql ocr");
    }

    private UnifiedSearchServiceImpl buildService() {
        return buildService(1);
    }

    private UnifiedSearchServiceImpl buildService(int candidateMultiplier) {
        AppSearchProperties props = new AppSearchProperties();
        props.getRrf().setCandidateMultiplier(candidateMultiplier);
        props.getRerank().setWindowSize(2);
        props.getRerank().setWindowMin(1);
        props.getRerank().setWindowMax(10);
        return new UnifiedSearchServiceImpl(
                kbSegmentSearchPort,
                kbQueryEmbeddingService,
                searchRerankPort,
                props,
                new SimpleMeterRegistry()
        );
    }

    private KbSegmentDocument buildDoc(String segmentId,
                                       String assetType,
                                       String segmentType,
                                       String title,
                                       String contentText,
                                       String ocrText,
                                       Integer pageNo) {
        KbSegmentDocument doc = new KbSegmentDocument();
        doc.setSegmentId(segmentId);
        doc.setAssetId("asset-" + segmentId);
        doc.setAssetType(assetType);
        doc.setSegmentType(segmentType);
        doc.setTitle(title);
        doc.setContentText(contentText);
        doc.setOcrText(ocrText);
        doc.setSourceRef("oss://" + segmentId);
        doc.setPageNo(pageNo);
        if ("IMAGE_OCR_BLOCK".equals(segmentType) || "IMAGE_CAPTION".equals(segmentType)) {
            doc.setTags(List.of("mysql"));
        }
        if ("IMAGE_OCR_BLOCK".equals(segmentType)) {
            doc.setBbox(Bbox.builder()
                    .x(120)
                    .y(80)
                    .width(360)
                    .height(48)
                    .unit("PIXEL")
                    .build());
            doc.setImageWidth(1920);
            doc.setImageHeight(1080);
        }
        if ("TEXT_CHUNK".equals(segmentType)) {
            doc.setChunkOrder(0);
        }
        return doc;
    }
}
