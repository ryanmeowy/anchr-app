package com.anchr.core.search.infrastructure.persistence.es.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.anchr.core.search.infrastructure.persistence.es.SearchResultConvertor;
import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.search.domain.model.HybridSearchParamDTO;
import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;
import com.anchr.core.search.infrastructure.persistence.es.query.factory.SearchRequestFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Hybrid search implementation
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ImageRepositoryImpl implements ImageRepositoryCustom {

    private final ElasticsearchClient esClient;
    private final SearchResultConvertor converter;
    private final SearchRequestFactory searchRequestFactory;

    @Deprecated(since = "2026-04", forRemoval = false)
    @Override
    public List<ImageSearchResultDTO> hybridSearch(HybridSearchParamDTO paramDTO) {
        try {
            SearchRequest request = searchRequestFactory.buildHybrid(paramDTO);
            SearchResponse<ImageDocument> response = esClient.search(request, ImageDocument.class);
            return converter.convert2Doc(response);
        } catch (Exception e) {
            log.error("Hybrid search execution failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public List<ImageSearchResultDTO> hybridSearchNativeRrf(HybridSearchParamDTO paramDTO,
                                                            Integer rankConstant,
                                                            Integer rankWindowSize) {
        try {
            SearchRequest request = searchRequestFactory.buildHybridNativeRrf(paramDTO, rankConstant, rankWindowSize);
            SearchResponse<ImageDocument> response = esClient.search(request, ImageDocument.class);
            return converter.convert2Doc(response);
        } catch (Exception e) {
            log.error("Hybrid native RRF search execution failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public List<ImageSearchResultDTO> searchSimilar(List<Float> vector, Integer topK, String excludeDocId) {
        try {
            SearchRequest request = searchRequestFactory.buildSimilar(vector, topK, excludeDocId);
            SearchResponse<ImageDocument> response = esClient.search(request, ImageDocument.class);
            return converter.convert2Doc(response);
        } catch (Exception e) {
            log.error("Execution of finding similar failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public List<ImageSearchResultDTO> vectorSearch(List<Float> vector, Integer topK) {
        try {
            SearchRequest request = searchRequestFactory.buildVectorOnly(vector, topK);
            SearchResponse<ImageDocument> response = esClient.search(request, ImageDocument.class);
            return converter.convert2Doc(response);
        } catch (Exception e) {
            log.error("Execution of vector-only search failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public List<ImageSearchResultDTO> textSearch(String keyword, Integer limit, Boolean enableOcr) {
        try {
            SearchRequest request = searchRequestFactory.buildTextOnly(keyword, limit, enableOcr);
            SearchResponse<ImageDocument> response = esClient.search(request, ImageDocument.class);
            return converter.convert2Doc(response);
        } catch (Exception e) {
            log.error("Execution of text-only search failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }

    @Override
    public ImageDocument findDuplicate(List<Float> vector, double threshold) {
        try {
            SearchRequest request = searchRequestFactory.buildDuplicate(vector, threshold);
            SearchResponse<ImageDocument> response = esClient.search(request, ImageDocument.class);
            if (response.hits() == null || response.hits().hits() == null || response.hits().hits().isEmpty()) {
                log.warn("Duplicate check returned empty hits");
                return null;
            }
            return response.hits().hits().getFirst().source();
        } catch (Exception e) {
            log.error("Duplicate check failed", e);
            throw new BusinessException(ApiError.SEARCH_BACKEND_UNAVAILABLE);
        }
    }
}
