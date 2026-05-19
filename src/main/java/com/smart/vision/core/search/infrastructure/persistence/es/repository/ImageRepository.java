package com.smart.vision.core.search.infrastructure.persistence.es.repository;

import com.smart.vision.core.search.domain.repository.ImageSearchRepository;
import com.smart.vision.core.search.infrastructure.persistence.es.document.ImageDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * Elasticsearch data access layer;
 * Generic 1: Entity class;
 * Generic 2: Primary key type;
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Repository
public interface ImageRepository extends ElasticsearchRepository<ImageDocument, String>, ImageRepositoryCustom, ImageSearchRepository {
}
