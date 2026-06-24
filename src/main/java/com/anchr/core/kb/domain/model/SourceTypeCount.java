package com.anchr.core.kb.domain.model;

/**
 * Count of active assets for a single file type within a knowledge base.
 */
public record SourceTypeCount(String type, int count) {
}
