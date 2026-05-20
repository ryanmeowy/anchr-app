package com.anchr.core.ingestion.domain.repository;

import com.anchr.core.ingestion.domain.model.TextChunk;

import java.util.List;

/**
 * Repository for persisted text segments/chunks.
 */
public interface TextSegmentRepository {

    void save(String assetId, List<TextChunk> chunks);
}
