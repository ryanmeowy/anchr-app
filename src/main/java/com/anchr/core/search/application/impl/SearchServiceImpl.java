package com.anchr.core.search.application.impl;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.application.SearchService;
import com.anchr.core.search.application.support.HotSearchManager;
import com.anchr.core.search.application.support.SearchCursorCodec;
import com.anchr.core.search.application.support.SearchCursorPayload;
import com.anchr.core.search.application.support.SearchPageSession;
import com.anchr.core.search.application.support.SearchSessionManager;
import com.anchr.core.search.config.AppSearchProperties;
import com.anchr.core.search.domain.model.HitSourceEnum;
import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.domain.model.StrategyTypeEnum;
import com.anchr.core.search.domain.port.SearchEmbeddingPort;
import com.anchr.core.search.domain.port.SearchObjectStoragePort;
import com.anchr.core.search.domain.strategy.RetrievalStrategy;
import com.anchr.core.search.domain.strategy.StrategyFactory;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import com.anchr.core.search.infrastructure.persistence.es.repository.ImageRepository;
import com.anchr.core.search.interfaces.assembler.ImageDocConvertor;
import com.anchr.core.search.interfaces.rest.dto.GraphTripleDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchExplainDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.anchr.core.common.constant.CacheConstant.IMAGE_MD5_CACHE_PREFIX;
import static com.anchr.core.common.constant.CacheConstant.VECTOR_CACHE_PREFIX;
import static com.anchr.core.common.constant.EmbeddingConstant.DEFAULT_TOP_K;
import static com.anchr.core.common.constant.EmbeddingConstant.QUALITY_MIN_RESULTS;
import static com.anchr.core.common.constant.EmbeddingConstant.QUALITY_RATIO_CUTOFF;
import static com.anchr.core.common.constant.EmbeddingConstant.SIMILARITY_TOP_K;
import static com.anchr.core.search.domain.util.ScoreUtil.mapScoreForSimilar;

/**
 * Smart search service implementation
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    private int defaultPageSize;
    private int pageMaxWindow;
    private boolean rrfNativeEnabled;

    private final SearchEmbeddingPort embeddingPort;
    private final ImageRepository imageRepository;
    private final ImageDocConvertor imageDocConvertor;
    private final HotSearchManager hotSearchManager;
    private final StrategyFactory strategyFactory;
    private final RedisTemplate<String, List<Float>> redisTemplate;
    private final SearchObjectStoragePort objectStoragePort;
    private final SearchSessionManager searchSessionManager;
    private final SearchCursorCodec searchCursorCodec;
    private final AppSearchProperties appSearchProperties;

    @PostConstruct
    void initSearchConfig() {
        this.defaultPageSize = appSearchProperties.getPage().getDefaultPageSize();
        this.pageMaxWindow = appSearchProperties.getPage().getMaxWindow();
        this.rrfNativeEnabled = appSearchProperties.getRrf().isNativeEnabled();
    }

    public List<SearchResultDTO> search(SearchQueryDTO query) {
        hotSearchManager.incrementScore(query.getKeyword());
        RetrievalStrategy strategy = strategyFactory.getStrategy(query.getSearchType());
        StrategyTypeEnum effectiveStrategy = strategy.getType();
        List<Float> queryVector = requiresTextEmbedding(effectiveStrategy) ? getOrCreateQueryVector(query.getKeyword()) : null;
        List<ImageSearchResultDTO> docs = strategy.search(query, queryVector);
        int maxResults = query.getLimit() != null && query.getLimit() > 0 ? query.getLimit() : docs.size();
        List<ImageSearchResultDTO> filteredDocs = shouldApplySimilarityFilter(effectiveStrategy)
                ? similarFilter(docs, maxResults)
                : docs.stream().limit(maxResults).collect(Collectors.toList());
        log.info("Search completed, number of results before filter: {}, after filter: {}", docs.size(), filteredDocs.size());
        boolean enableOcr = query.getEnableOcr() == null || query.getEnableOcr();
        List<ImageSearchResultDTO> reranked = shouldApplyManualRerank(effectiveStrategy)
                ? manualRerank(filteredDocs, query.getKeyword(), enableOcr)
                : filteredDocs;
        List<SearchResultDTO> dtoList = imageDocConvertor.convert2SearchResultDTO(reranked);
        applyVectorHitStatusForSearch(dtoList, reranked, query.getKeyword(), enableOcr, effectiveStrategy);
        applyExplainForSearch(dtoList, reranked, query.getKeyword(), enableOcr, effectiveStrategy);
        return dtoList;
    }

    public List<SearchResultDTO> searchSimilarById(String docId) {
        ImageDocument sourceDoc = imageRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("Image does not exist or has been deleted"));

        List<Float> embedding = sourceDoc.getImageEmbedding();

        if (CollectionUtils.isEmpty(embedding)) {
            throw new RuntimeException("The image has not been vectorized yet");
        }
        List<ImageSearchResultDTO> similarDocs = imageRepository.searchSimilar(embedding, SIMILARITY_TOP_K, docId);
        List<ImageSearchResultDTO> filtered = similarFilter(similarDocs, SIMILARITY_TOP_K);
        applySimilarDisplayScore(filtered);
        List<SearchResultDTO> dtoList = imageDocConvertor.convert2SearchResultDTO(filtered);
        applyFixedVectorStatus(dtoList, "VECTOR_ONLY_LIKE");
        applyExplainForSearch(dtoList, filtered, "", false, StrategyTypeEnum.VECTOR_ONLY);
        return dtoList;
    }

    public List<ImageSearchResultDTO> similarFilter(List<ImageSearchResultDTO> results, int maxResults) {
        if (results.isEmpty()) return results;
        List<ImageSearchResultDTO> filtered = new ArrayList<>();
        int hardLimit = Math.max(1, Math.min(maxResults, results.size()));
        double prevKeptScore = -1d;

        for (int i = 0; i < hardLimit; i++) {
            ImageSearchResultDTO current = results.get(i);
            double currScore = decisionScore(current);

            if (currScore < appSearchProperties.getQualityAbsoluteMinScore()) {
                break;
            }

            if (filtered.isEmpty()) {
                filtered.add(current);
                prevKeptScore = currScore;
                continue;
            }

            boolean needBypassRatioCutoff = filtered.size() < QUALITY_MIN_RESULTS;
            if (!needBypassRatioCutoff) {
                boolean isBigDrop = prevKeptScore <= 0 || (currScore / prevKeptScore) < QUALITY_RATIO_CUTOFF;
                if (isBigDrop) {
                    break;
                }
            }

            filtered.add(current);
            prevKeptScore = currScore;
        }
        return filtered;
    }

    private List<Float> getVectorFromCache(String text) {
        if (!StringUtils.hasText(text)) return null;
        String key = buildVectorCacheKey(text);
        return redisTemplate.opsForValue().get(key);
    }

    private void cacheVector(String text, List<Float> vector) {
        if (!StringUtils.hasText(text) || vector == null || vector.isEmpty()) return;
        String key = buildVectorCacheKey(text);
        redisTemplate.opsForValue().set(key, vector, 24, TimeUnit.HOURS);
    }

    private String buildVectorCacheKey(String text) {
        return String.format("%s%s", VECTOR_CACHE_PREFIX, DigestUtils.md5DigestAsHex(text.trim().toLowerCase().getBytes()));
    }

    @Override
    public List<SearchResultDTO> searchByImage(MultipartFile file, int limit) {
        try (InputStream is = file.getInputStream()) {
            String md5 = DigestUtils.md5DigestAsHex(is);
            String cacheKey = String.format("%s%s", IMAGE_MD5_CACHE_PREFIX, md5);
            List<Float> vector = redisTemplate.opsForValue().get(cacheKey);
            if (vector != null) {
                log.info("Cache hit, MD5: {}", md5);
            } else {
                log.info("Cache missed, processing new image, MD5: {}", md5);
                String objectKey = objectStoragePort.uploadFile(file);
                String tempAiUrl = objectStoragePort.buildAiImageInput(objectKey, SearchObjectStoragePort.AiInputValidity.SHORT);
                vector = embeddingPort.embedImage(tempAiUrl);
                redisTemplate.opsForValue().set(cacheKey, vector, 24, TimeUnit.HOURS);
            }
            RetrievalStrategy strategy = strategyFactory.getStrategy(StrategyTypeEnum.IMAGE_TO_IMAGE.getCode());
            List<ImageSearchResultDTO> imageSearchResultDTOS = strategy.search(null, vector);
            int maxResults = limit > 0 ? limit : imageSearchResultDTOS.size();
            List<ImageSearchResultDTO> filtered = similarFilter(imageSearchResultDTOS, maxResults);
            applySimilarDisplayScore(filtered);
            List<SearchResultDTO> dtoList = imageDocConvertor.convert2SearchResultDTO(filtered);
            applyFixedVectorStatus(dtoList, "VECTOR_ONLY_LIKE");
            applyExplainForSearch(dtoList, filtered, "", false, StrategyTypeEnum.IMAGE_TO_IMAGE);
            return dtoList;
        } catch (Exception e) {
            log.error("Failed to search by image", e);
            throw new BusinessException(ApiError.IMAGE_SEARCH_FAILED);
        }
    }

    @Override
    public SearchPageDTO searchPage(SearchPageQueryDTO query) {
        int pageSize = resolvePageSize(query == null ? null : query.getLimit());
        if (query == null) {
            throw new IllegalArgumentException("Paged search request cannot be empty.");
        }
        String queryFingerprint = buildPageQueryFingerprint(query, pageSize);

        if (!StringUtils.hasText(query.getCursor())) {
            SearchQueryDTO initialQuery = toInitialSearchQuery(query, pageSize);
            List<SearchResultDTO> fullResults = search(initialQuery);
            SearchPageSession session = searchSessionManager.create(queryFingerprint, fullResults);
            return buildPagedResponse(fullResults, 0, pageSize, session);
        }

        SearchCursorPayload cursorPayload = searchCursorCodec.decode(query.getCursor());
        if (cursorPayload.getExpireAt() <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("Cursor has expired, please search again.");
        }
        if (!queryFingerprint.equals(cursorPayload.getQueryFingerprint())) {
            throw new IllegalArgumentException("Cursor does not match the current query.");
        }
        SearchPageSession session = searchSessionManager.find(cursorPayload.getSessionId())
                .orElseThrow(() -> new IllegalArgumentException("Cursor has expired, please search again."));
        if (!queryFingerprint.equals(session.getQueryFingerprint())) {
            throw new IllegalArgumentException("Cursor fingerprint mismatch.");
        }
        if (session.getExpireAt() <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("Cursor has expired, please search again.");
        }
        return buildPagedResponse(session.getResults(), cursorPayload.getOffset(), pageSize, session);
    }

    private List<ImageSearchResultDTO> manualRerank(List<ImageSearchResultDTO> list, String keyword, boolean enableOcr) {
        if (!StringUtils.hasText(keyword) || CollectionUtils.isEmpty(list)) {
            return list;
        }
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);

        return list.stream()
                .sorted((o1, o2) -> {
                    int o1HitScore = keywordHitScore(o1, normalizedKeyword, enableOcr);
                    int o2HitScore = keywordHitScore(o2, normalizedKeyword, enableOcr);
                    if (o1HitScore != o2HitScore) {
                        return Integer.compare(o2HitScore, o1HitScore);
                    }
                    return Double.compare(decisionScore(o2), decisionScore(o1));
                })
                .collect(Collectors.toList());
    }

    private int keywordHitScore(ImageSearchResultDTO result, String normalizedKeyword, boolean enableOcr) {
        if (result == null || result.getDocument() == null) {
            return 0;
        }

        int score = 0;
        ImageDocument doc = result.getDocument();

        if (containsIgnoreCase(doc.getFileName(), normalizedKeyword)) {
            score += 3;
        }
        if (!CollectionUtils.isEmpty(doc.getTags())) {
            boolean tagHit = doc.getTags().stream().anyMatch(tag -> containsIgnoreCase(tag, normalizedKeyword));
            if (tagHit) {
                score += 2;
            }
        }
        if (enableOcr && containsIgnoreCase(doc.getOcrContent(), normalizedKeyword)) {
            score += 4;
        }
        return score;
    }

    private boolean containsIgnoreCase(String text, String normalizedKeyword) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(normalizedKeyword)
                && text.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private double decisionScore(ImageSearchResultDTO result) {
        if (result == null) {
            return 0;
        }
        Double rawScore = result.getRawScore();
        if (rawScore != null) {
            return rawScore;
        }
        Double fallbackScore = result.getScore();
        return fallbackScore == null ? 0 : fallbackScore;
    }

    private List<Float> getOrCreateQueryVector(String keyword) {
        List<Float> queryVector = getVectorFromCache(keyword);
        if (CollectionUtils.isEmpty(queryVector)) {
            log.info("vector cache missed, processing new text, keyword: {}", keyword);
            queryVector = embeddingPort.embedText(keyword);
            cacheVector(keyword, queryVector);
            return queryVector;
        }
        log.info("vector cache hit, processing new text, keyword: {}", keyword);
        return queryVector;
    }

    private boolean requiresTextEmbedding(StrategyTypeEnum strategyType) {
        return strategyType == StrategyTypeEnum.HYBRID || strategyType == StrategyTypeEnum.VECTOR_ONLY;
    }

    private boolean shouldApplySimilarityFilter(StrategyTypeEnum strategyType) {
        return false;
    }

    private boolean shouldApplyManualRerank(StrategyTypeEnum strategyType) {
        return strategyType != StrategyTypeEnum.HYBRID;
    }

    private SearchPageDTO buildPagedResponse(List<SearchResultDTO> allResults,
                                             int offset,
                                             int pageSize,
                                             SearchPageSession session) {
        List<SearchResultDTO> safeResults = allResults == null ? List.of() : allResults;
        int safeOffset = Math.max(0, Math.min(offset, safeResults.size()));
        int end = Math.min(safeResults.size(), safeOffset + Math.max(1, pageSize));
        List<SearchResultDTO> pageItems = new ArrayList<>(safeResults.subList(safeOffset, end));
        boolean hasMore = end < safeResults.size();
        String nextCursor = null;
        if (hasMore) {
            SearchCursorPayload nextPayload = new SearchCursorPayload(
                    session.getSessionId(),
                    end,
                    session.getQueryFingerprint(),
                    session.getExpireAt()
            );
            nextCursor = searchCursorCodec.encode(nextPayload);
        }
        return SearchPageDTO.builder()
                .items(pageItems)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    private SearchQueryDTO toInitialSearchQuery(SearchPageQueryDTO pageQuery, int pageSize) {
        SearchQueryDTO query = new SearchQueryDTO();
        query.setKeyword(pageQuery.getKeyword());
        query.setSearchType(pageQuery.getSearchType());
        query.setEnableOcr(pageQuery.getEnableOcr());

        int requestedTopK = pageQuery.getTopK() == null ? DEFAULT_TOP_K : pageQuery.getTopK();
        int safeTopK = clamp(requestedTopK, 1, 200);
        int safeWindowCap = clamp(pageMaxWindow, 1, 200);
        int windowSize = Math.min(safeWindowCap, Math.max(pageSize, safeTopK));
        query.setTopK(safeTopK);
        query.setLimit(windowSize);
        return query;
    }

    private int resolvePageSize(Integer limit) {
        int fallback = Math.max(1, defaultPageSize);
        if (limit == null || limit <= 0) {
            return fallback;
        }
        return clamp(limit, 1, 100);
    }

    private String buildPageQueryFingerprint(SearchPageQueryDTO query, int pageSize) {
        String normalized = String.join("|",
                normalizeFingerprintToken(query.getKeyword()),
                normalizeFingerprintToken(query.getSearchType()),
                String.valueOf(clamp(query.getTopK() == null ? DEFAULT_TOP_K : query.getTopK(), 1, 200)),
                String.valueOf(query.getEnableOcr() == null || query.getEnableOcr()),
                String.valueOf(pageSize)
        );
        return DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeFingerprintToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void applySimilarDisplayScore(List<ImageSearchResultDTO> results) {
        if (CollectionUtils.isEmpty(results)) {
            return;
        }
        for (ImageSearchResultDTO result : results) {
            result.setScore(mapScoreForSimilar(result.getRawScore()));
        }
    }

    private void applyVectorHitStatusForSearch(List<SearchResultDTO> dtoList,
                                               List<ImageSearchResultDTO> sourceDocs,
                                               String keyword,
                                               boolean enableOcr,
                                               StrategyTypeEnum strategyType) {
        if (CollectionUtils.isEmpty(dtoList) || CollectionUtils.isEmpty(sourceDocs)) {
            return;
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int bound = Math.min(dtoList.size(), sourceDocs.size());

        for (int i = 0; i < bound; i++) {
            SearchResultDTO dto = dtoList.get(i);
            ImageSearchResultDTO source = sourceDocs.get(i);
            ImageDocument doc = source == null ? null : source.getDocument();
            dto.setVectorHitStatus(computeVectorHitStatus(doc, source, normalizedKeyword, enableOcr, strategyType));
        }
    }

    private void applyFixedVectorStatus(List<SearchResultDTO> dtoList, String status) {
        if (CollectionUtils.isEmpty(dtoList)) {
            return;
        }
        for (SearchResultDTO dto : dtoList) {
            dto.setVectorHitStatus(status);
        }
    }

    private void applyExplainForSearch(List<SearchResultDTO> dtoList,
                                       List<ImageSearchResultDTO> sourceDocs,
                                       String keyword,
                                       boolean enableOcr,
                                       StrategyTypeEnum strategyType) {
        if (CollectionUtils.isEmpty(dtoList) || CollectionUtils.isEmpty(sourceDocs)) {
            return;
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int bound = Math.min(dtoList.size(), sourceDocs.size());
        for (int i = 0; i < bound; i++) {
            SearchResultDTO dto = dtoList.get(i);
            ImageSearchResultDTO source = sourceDocs.get(i);
            ImageDocument doc = source == null ? null : source.getDocument();
            dto.setExplain(buildExplain(doc, source, normalizedKeyword, enableOcr, strategyType));
        }
    }

    private String computeVectorHitStatus(ImageDocument doc,
                                          ImageSearchResultDTO source,
                                          String normalizedKeyword,
                                          boolean enableOcr,
                                          StrategyTypeEnum strategyType) {
        if (strategyType == StrategyTypeEnum.TEXT_ONLY) {
            return "TEXT_ONLY";
        }
        if (strategyType == StrategyTypeEnum.VECTOR_ONLY || strategyType == StrategyTypeEnum.IMAGE_TO_IMAGE) {
            return "VECTOR_ONLY_LIKE";
        }
        if (strategyType == StrategyTypeEnum.HYBRID) {
            boolean textHit = hasTextHit(doc, source, normalizedKeyword, enableOcr);
            boolean vectorHit = isVectorEvidenceHit(source, strategyType);
            if (vectorHit && textHit) {
                return "VECTOR_AND_TEXT";
            }
            if (vectorHit) {
                return "VECTOR_ONLY_LIKE";
            }
            if (textHit) {
                return "TEXT_ONLY";
            }
            return "VECTOR_ONLY_LIKE";
        }
        return "TEXT_ONLY";
    }

    private SearchExplainDTO buildExplain(ImageDocument doc,
                                          ImageSearchResultDTO source,
                                          String normalizedKeyword,
                                          boolean enableOcr,
                                          StrategyTypeEnum strategyType) {
        boolean allowTextExplain = strategyType == StrategyTypeEnum.HYBRID || strategyType == StrategyTypeEnum.TEXT_ONLY;
        Set<String> highlightFields = allowTextExplain ? resolveHighlightFields(source) : Set.of();
        boolean filenameHit = allowTextExplain && highlightFields.contains("fileName");
        boolean ocrHit = allowTextExplain && highlightFields.contains("ocrContent");
        boolean tagHit = allowTextExplain && highlightFields.contains("tags");

        boolean graphHit = allowTextExplain && (
                highlightFields.contains("relations.s")
                        || highlightFields.contains("relations.p")
                        || highlightFields.contains("relations.o")
        );
        boolean vectorHit = isVectorEvidenceHit(source, strategyType);

        LinkedHashSet<String> hitSources = new LinkedHashSet<>();
        if (vectorHit) {
            hitSources.add(HitSourceEnum.VECTOR.name());
        }
        if (filenameHit) {
            hitSources.add(HitSourceEnum.FILENAME.name());
        }
        if (ocrHit) {
            hitSources.add(HitSourceEnum.OCR.name());
        }
        if (tagHit) {
            hitSources.add(HitSourceEnum.TAG.name());
        }
        if (graphHit) {
            hitSources.add(HitSourceEnum.GRAPH.name());
        }

        return SearchExplainDTO.builder()
                .strategyEffective(strategyType.getCode())
                .hitSources(new ArrayList<>(hitSources))
                .matchedBy(SearchExplainDTO.MatchedBy.builder()
                        .vector(vectorHit)
                        .filename(filenameHit)
                        .ocr(ocrHit)
                        .tag(tagHit)
                        .graph(graphHit)
                        .build())
                .build();
    }

    private boolean hasTextHit(ImageDocument doc,
                               ImageSearchResultDTO source,
                               String normalizedKeyword,
                               boolean enableOcr) {
        if (source != null && Boolean.TRUE.equals(source.getTextRecallHit())) {
            return true;
        }
        if (!resolveHighlightFields(source).isEmpty()) {
            return true;
        }
        if (doc == null || !StringUtils.hasText(normalizedKeyword)) {
            return false;
        }
        if (hasOcrHit(doc, normalizedKeyword, enableOcr)) {
            return true;
        }
        if (hasTagHit(doc, normalizedKeyword)) {
            return true;
        }
        if (hasFilenameHit(doc, normalizedKeyword)) {
            return true;
        }
        if (hasGraphHit(doc, normalizedKeyword)) {
            return true;
        }
        return false;
    }

    private Set<String> resolveHighlightFields(ImageSearchResultDTO source) {
        if (source == null || CollectionUtils.isEmpty(source.getHighlightFields())) {
            return Set.of();
        }
        Set<String> fields = new HashSet<>();
        for (String field : source.getHighlightFields()) {
            if (StringUtils.hasText(field)) {
                fields.add(field.trim());
            }
        }
        return fields;
    }

    private boolean isVectorEvidenceHit(ImageSearchResultDTO source,
                                        StrategyTypeEnum strategyType) {
        if (strategyType == StrategyTypeEnum.VECTOR_ONLY || strategyType == StrategyTypeEnum.IMAGE_TO_IMAGE) {
            return true;
        }
        if (strategyType == StrategyTypeEnum.TEXT_ONLY) {
            return false;
        }
        if (strategyType != StrategyTypeEnum.HYBRID) {
            return false;
        }
        if (source != null && source.getVectorRecallHit() != null) {
            return source.getVectorRecallHit();
        }
        if (rrfNativeEnabled && source != null && source.getTextRecallHit() != null) {
            return !source.getTextRecallHit();
        }
        // Strict mode: if vector recall evidence is unknown, do not mark as VECTOR.
        return false;
    }

    private boolean hasFilenameHit(ImageDocument doc, String normalizedKeyword) {
        return doc != null && containsIgnoreCase(doc.getFileName(), normalizedKeyword);
    }

    private boolean hasOcrHit(ImageDocument doc, String normalizedKeyword, boolean enableOcr) {
        return enableOcr && doc != null && containsIgnoreCase(doc.getOcrContent(), normalizedKeyword);
    }

    private boolean hasTagHit(ImageDocument doc, String normalizedKeyword) {
        if (doc == null || CollectionUtils.isEmpty(doc.getTags())) {
            return false;
        }
        return doc.getTags().stream().anyMatch(tag -> containsIgnoreCase(tag, normalizedKeyword));
    }

    private boolean hasGraphHit(ImageDocument doc, String normalizedKeyword) {
        if (doc == null || CollectionUtils.isEmpty(doc.getRelations())) {
            return false;
        }
        for (GraphTripleDTO triple : doc.getRelations()) {
            if (triple == null) {
                continue;
            }
            if (containsIgnoreCase(triple.getS(), normalizedKeyword)
                    || containsIgnoreCase(triple.getP(), normalizedKeyword)
                    || containsIgnoreCase(triple.getO(), normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }
}
