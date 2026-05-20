
package com.anchr.core.search.infrastructure.persistence.es.repository;

import com.anchr.core.search.domain.model.HybridSearchParamDTO;
import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.infrastructure.persistence.es.document.ImageDocument;

import java.util.List;

/**
 * Custom extension interface for defining complex hybrid search methods;
 *
 * @author Ryan
 * @since 2025/12/15
 */
public interface ImageRepositoryCustom {
    /**
     * Hybrid search: vector + OCR text
     *
     * @return list of matching documents
     */
    @Deprecated(since = "2026-04", forRemoval = false)
    List<ImageSearchResultDTO> hybridSearch(HybridSearchParamDTO paramDTO);

    /**
     * Hybrid search powered by Elasticsearch native retriever RRF.
     *
     * @param paramDTO hybrid query parameters
     * @param rankConstant RRF rank constant
     * @param rankWindowSize RRF rank window size
     * @return list of matching documents
     */
    List<ImageSearchResultDTO> hybridSearchNativeRrf(HybridSearchParamDTO paramDTO, Integer rankConstant, Integer rankWindowSize);

    /**
     * Search for similar documents based on vector (supports excluding specific ID)
     * @param vector Array of vectors
     * @param topK Number of results
     * @param excludeDocId Document ID to exclude (usually exclude itself when searching for similar)
     * @return List of documents
     */
    List<ImageSearchResultDTO> searchSimilar(List<Float> vector, Integer topK, String excludeDocId);

    /**
     * Vector-only retrieval without document exclusion.
     *
     * @param vector query embedding
     * @param topK number of candidates
     * @return list of matching documents
     */
    List<ImageSearchResultDTO> vectorSearch(List<Float> vector, Integer topK);

    /**
     * Text-only retrieval (BM25 style) over OCR/tags/file name.
     *
     * @param keyword search keyword
     * @param limit max number of returned results
     * @param enableOcr whether OCR field should participate in matching
     * @return list of matching documents
     */
    List<ImageSearchResultDTO> textSearch(String keyword, Integer limit, Boolean enableOcr);

    /**
     * Check if there is an extremely similar image (used for deduplication)
     * @param vector Vector to be checked
     * @param threshold Similarity threshold
     * @return Existing similar image document, or null if none exists
     */
    @Deprecated
    ImageDocument findDuplicate(List<Float> vector, double threshold);


}
