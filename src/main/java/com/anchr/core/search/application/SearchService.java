package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchPageQueryDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * search service interface that provides intelligent image search capabilities
 * Supports multiple search strategies including hybrid search, vector-only search,
 * text-only search, and image-to-image search;
 *
 * @author Ryan
 * @since 2025/12/15
 */
public interface SearchService {

    /**
     * Perform intelligent search based on the provided search parameters
     * The search strategy is determined by the strategy type in the query DTO
     *
     * @param query search parameters including:
     *              {@code topK} as vector recall size (KNN k, strategy-dependent),
     *              {@code limit} as final response size upper bound,
     *              and search strategy configuration
     * @return list of search results with image metadata, scores, and highlighted content
     * @see SearchQueryDTO#getKeyword() search keyword or text query
     * @see SearchQueryDTO#getTopK() vector recall size (ignored by TEXT_ONLY)
     * @see SearchQueryDTO#getLimit() final response size upper bound
     * @see SearchQueryDTO#getSimilarity() deprecated, retrieval now follows topK-first strategy
     * @see SearchQueryDTO#getEnableOcr() whether to enable OCR-based text search
     */
    List<SearchResultDTO> search(SearchQueryDTO query);

    /**
     * Perform vector-based search using the provided document ID
     *
     * @param docId unique identifier of the document to search by vector
     * @return list of search results with image metadata, scores, and highlighted content
     */
    List<SearchResultDTO> searchSimilarById(String docId);

    List<SearchResultDTO> searchByImage(MultipartFile file, int limit);

    /**
     * Perform paged search with cursor-based pagination.
     *
     * @param query paged query where {@code limit} is page size and final response upper bound;
     *              {@code topK} is vector recall size for first-page retrieval when strategy uses vectors
     */
    SearchPageDTO searchPage(SearchPageQueryDTO query);
}
