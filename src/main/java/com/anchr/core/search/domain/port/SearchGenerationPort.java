package com.anchr.core.search.domain.port;

/**
 * Domain port for search-side text generation (query rewrite, follow-up questions, etc.).
 */
public interface SearchGenerationPort {

    /**
     * Generate text from a prompt.
     */
    String generateText(String prompt);
}
