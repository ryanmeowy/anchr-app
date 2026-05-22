package com.anchr.core.search.application.impl;

import com.anchr.core.activity.application.ActivityEventService;
import com.anchr.core.search.application.KbQueryEmbeddingService;
import com.anchr.core.search.application.KbScopeResolver;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.Bbox;
import com.anchr.core.search.domain.model.KbAssetTypeEnum;
import com.anchr.core.search.domain.model.KbSearchFilter;
import com.anchr.core.search.domain.model.KbSegmentHit;
import com.anchr.core.search.domain.model.Segment;
import com.anchr.core.search.domain.model.SegmentType;
import com.anchr.core.search.domain.port.SearchRerankPort;
import com.anchr.core.search.domain.port.SearchRerankPort.RerankItem;
import com.anchr.core.search.domain.repository.KbSegmentRepository;
import com.anchr.core.search.interfaces.rest.dto.KbSearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchResultDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedSearchServiceImplTest {

    @Mock
    private KbSegmentRepository kbSegmentRepository;
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
        when(kbSegmentRepository.textSearch(eq("mysql"), eq(5), any(KbSearchFilter.class))).thenReturn(List.of(
                KbSegmentHit.builder()
                        .segment(buildSegment("seg-1", KbAssetTypeEnum.TEXT, SegmentType.TEXT_CHUNK, "mysql notes", "mysql chunk", null, 2))
                        .rawScore(3.2d)
                        .highlights(Map.of("contentText", "<em>mysql</em> chunk"))
                        .highlightFields(List.of("contentText"))
                        .build()
        ));
        when(kbSegmentRepository.vectorSearch(eq(List.of(0.1f, 0.2f)), eq(5), any(KbSearchFilter.class))).thenReturn(List.of(
                KbSegmentHit.builder()
                        .segment(buildSegment("seg-1", KbAssetTypeEnum.TEXT, SegmentType.TEXT_CHUNK, "mysql notes", "mysql chunk", null, 2))
                        .rawScore(1.1d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build(),
                KbSegmentHit.builder()
                        .segment(buildSegment("seg-2", KbAssetTypeEnum.IMAGE, SegmentType.IMAGE_CAPTION, "diagram", "mysql architecture", null, null))
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
        when(kbSegmentRepository.textSearch(eq("mysql"), eq(5), any(KbSearchFilter.class))).thenReturn(List.of(
                KbSegmentHit.builder()
                        .segment(buildSegment("seg-t1", KbAssetTypeEnum.TEXT, SegmentType.TEXT_CHUNK, "mysql notes", "mysql chunk", null, 3))
                        .rawScore(2.1d)
                        .highlights(Map.of("contentText", "<em>mysql</em> chunk"))
                        .highlightFields(List.of("contentText"))
                        .build()
        ));
        when(kbSegmentRepository.vectorSearch(eq(List.of(0.1f, 0.2f)), eq(5), any(KbSearchFilter.class))).thenReturn(List.of());

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
        when(kbSegmentRepository.textSearch(eq("mysql"), eq(5), any(KbSearchFilter.class))).thenReturn(List.of());
        when(kbSegmentRepository.vectorSearch(eq(List.of(0.1f, 0.2f)), eq(5), any(KbSearchFilter.class))).thenReturn(List.of(
                KbSegmentHit.builder()
                        .segment(buildSegment("seg-i1", KbAssetTypeEnum.IMAGE, SegmentType.IMAGE_OCR_BLOCK, "diagram", null, "mysql ocr text", null))
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
        when(kbSegmentRepository.textSearch(eq("mysql"), eq(3), any(KbSearchFilter.class))).thenReturn(List.of());
        when(kbSegmentRepository.vectorSearch(eq(List.of(0.1f, 0.2f)), eq(3), any(KbSearchFilter.class))).thenReturn(List.of(
                KbSegmentHit.builder()
                        .segment(buildSegment("seg-1", KbAssetTypeEnum.TEXT, SegmentType.TEXT_CHUNK, "mysql notes", "mysql chunk", null, 2))
                        .rawScore(1.0d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build(),
                KbSegmentHit.builder()
                        .segment(buildSegment("seg-2", KbAssetTypeEnum.TEXT, SegmentType.TEXT_CHUNK, "architecture", "mysql design", null, 3))
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
        when(kbSegmentRepository.textSearch(eq("mysql"), eq(12), any(KbSearchFilter.class))).thenReturn(List.of());
        when(kbSegmentRepository.vectorSearch(eq(List.of(0.1f, 0.2f)), eq(12), any(KbSearchFilter.class))).thenReturn(List.of());

        service.search(query);

        verify(kbSegmentRepository).textSearch(eq("mysql"), eq(12), any(KbSearchFilter.class));
        verify(kbSegmentRepository).vectorSearch(eq(List.of(0.1f, 0.2f)), eq(12), any(KbSearchFilter.class));
    }

    @Test
    void search_shouldAggregateByAssetAndExposeTopChunks() {
        UnifiedSearchServiceImpl service = buildService();

        KbSearchQueryDTO query = new KbSearchQueryDTO();
        query.setQuery("mysql");
        query.setTopK(5);
        query.setLimit(5);

        when(kbQueryEmbeddingService.embedQuery("mysql")).thenReturn(List.of(0.1f, 0.2f));
        when(kbSegmentRepository.textSearch(eq("mysql"), eq(5), any(KbSearchFilter.class))).thenReturn(List.of());

        Segment imageCaption = buildSegment("seg-a1", KbAssetTypeEnum.IMAGE, SegmentType.IMAGE_CAPTION, "db chart", "mysql chart", null, null, "asset-image-1");
        Segment imageOcr = buildSegment("seg-a2", KbAssetTypeEnum.IMAGE, SegmentType.IMAGE_OCR_BLOCK, "db chart", null, "mysql ocr summary text", null, "asset-image-1");

        when(kbSegmentRepository.vectorSearch(eq(List.of(0.1f, 0.2f)), eq(5), any(KbSearchFilter.class))).thenReturn(List.of(
                KbSegmentHit.builder()
                        .segment(imageCaption)
                        .rawScore(1.8d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build(),
                KbSegmentHit.builder()
                        .segment(imageOcr)
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
        assertThat(aggregated.getTopChunks().get(1).getAnchor().getBbox().getUnit()).isEqualTo("PIXEL");
        assertThat(aggregated.getTopChunks().get(1).getAnchor().getImageWidth()).isEqualTo(1920);
        assertThat(aggregated.getTopChunks().get(1).getAnchor().getImageHeight()).isEqualTo(1080);
        assertThat(aggregated.getThumbnail()).isEqualTo("oss://seg-a1");
        assertThat(aggregated.getOcrSummary()).contains("mysql ocr");
    }

    @Test
    void search_shouldExposePrimaryImageOcrAnchorWhenOcrBlockRanksFirst() {
        UnifiedSearchServiceImpl service = buildService();

        KbSearchQueryDTO query = new KbSearchQueryDTO();
        query.setQuery("mysql");
        query.setTopK(5);
        query.setLimit(5);

        when(kbQueryEmbeddingService.embedQuery("mysql")).thenReturn(List.of(0.1f, 0.2f));
        when(kbSegmentRepository.textSearch(eq("mysql"), eq(5), any(KbSearchFilter.class))).thenReturn(List.of());

        Segment imageOcr = buildSegment("seg-b1", KbAssetTypeEnum.IMAGE, SegmentType.IMAGE_OCR_BLOCK, "db chart", null, "mysql ocr summary text", null, "asset-image-2");
        Segment imageCaption = buildSegment("seg-b2", KbAssetTypeEnum.IMAGE, SegmentType.IMAGE_CAPTION, "db chart", "mysql chart", null, null, "asset-image-2");

        when(kbSegmentRepository.vectorSearch(eq(List.of(0.1f, 0.2f)), eq(5), any(KbSearchFilter.class))).thenReturn(List.of(
                KbSegmentHit.builder()
                        .segment(imageOcr)
                        .rawScore(1.9d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build(),
                KbSegmentHit.builder()
                        .segment(imageCaption)
                        .rawScore(1.2d)
                        .highlights(Map.of())
                        .highlightFields(List.of())
                        .build()
        ));

        List<KbSearchResultDTO> results = service.search(query);

        assertThat(results).hasSize(1);
        KbSearchResultDTO aggregated = results.getFirst();
        assertThat(aggregated.getSegmentId()).isEqualTo("seg-b1");
        assertThat(aggregated.getAnchor().getBbox().getX()).isEqualTo(120);
        assertThat(aggregated.getAnchor().getImageWidth()).isEqualTo(1920);
        assertThat(aggregated.getAnchor().getImageHeight()).isEqualTo(1080);
        assertThat(aggregated.getTopChunks().getFirst().getAnchor().getBbox().getUnit()).isEqualTo("PIXEL");
    }

    @Test
    void searchPageCursor_shouldClampLargeOffset() throws Exception {
        UnifiedSearchServiceImpl service = new UnifiedSearchServiceImpl(
                kbSegmentRepository,
                kbQueryEmbeddingService,
                mock(KbScopeResolver.class),
                searchRerankPort,
                new AppSearchProperties(),
                new SimpleMeterRegistry(),
                mock(ActivityEventService.class)
        );
        Method method = UnifiedSearchServiceImpl.class.getDeclaredMethod("decodeCursorOffset", String.class);
        method.setAccessible(true);
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("999999".getBytes(StandardCharsets.UTF_8));

        int offset = (int) method.invoke(service, cursor);

        assertThat(offset).isEqualTo(10_000);
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
        KbScopeResolver kbScopeResolver = mock(KbScopeResolver.class);
        when(kbScopeResolver.resolveVisibleKbIds(any())).thenReturn(List.of("kb-test"));
        return new UnifiedSearchServiceImpl(
                kbSegmentRepository,
                kbQueryEmbeddingService,
                kbScopeResolver,
                searchRerankPort,
                props,
                new SimpleMeterRegistry(),
                mock(ActivityEventService.class)
        );
    }

    private Segment buildSegment(String segmentId,
                                 KbAssetTypeEnum assetType,
                                 SegmentType segmentType,
                                 String title,
                                 String contentText,
                                 String ocrText,
                                 Integer pageNo) {
        return buildSegment(segmentId, assetType, segmentType, title, contentText, ocrText, pageNo, "asset-" + segmentId);
    }

    private Segment buildSegment(String segmentId,
                                 KbAssetTypeEnum assetType,
                                 SegmentType segmentType,
                                 String title,
                                 String contentText,
                                 String ocrText,
                                 Integer pageNo,
                                 String assetId) {
        return Segment.builder()
                .segmentId(segmentId)
                .assetId(assetId)
                .assetType(assetType)
                .segmentType(segmentType)
                .title(title)
                .contentText(contentText)
                .ocrText(ocrText)
                .sourceRef("oss://" + segmentId)
                .pageNo(pageNo)
                .chunkOrder(segmentType == SegmentType.TEXT_CHUNK ? 0 : null)
                .tags(assetType == KbAssetTypeEnum.IMAGE ? List.of("mysql") : null)
                .bbox(segmentType == SegmentType.IMAGE_OCR_BLOCK
                        ? Bbox.builder()
                        .x(120)
                        .y(80)
                        .width(360)
                        .height(48)
                        .unit("PIXEL")
                        .build()
                        : null)
                .imageWidth(segmentType == SegmentType.IMAGE_OCR_BLOCK ? 1920 : null)
                .imageHeight(segmentType == SegmentType.IMAGE_OCR_BLOCK ? 1080 : null)
                .build();
    }
}
