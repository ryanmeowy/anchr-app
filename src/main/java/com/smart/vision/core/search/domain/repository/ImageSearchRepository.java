package com.smart.vision.core.search.domain.repository;

import com.smart.vision.core.search.domain.model.ImageSearchResultDTO;

import java.util.List;

public interface ImageSearchRepository {

    List<ImageSearchResultDTO> searchSimilar(List<Float> vector, Integer topK, String excludeDocId);

    List<ImageSearchResultDTO> vectorSearch(List<Float> vector, Integer topK);

    List<ImageSearchResultDTO> textSearch(String keyword, Integer limit, Boolean enableOcr);
}
