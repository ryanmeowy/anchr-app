package com.anchr.core.ingestion.domain.port;

import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.model.TextParseResult;

/**
 * Parser abstraction for PDF/TXT/MD in Phase 1.
 */
public interface TextParserPort {

    /**
     * Whether the parser supports current asset.
     */
    boolean supports(TextAssetMetadata metadata);

    /**
     * Parse asset into unified parse result.
     */
    TextParseResult parse(TextAssetMetadata metadata);

    /**
     * Parser name for observability.
     */
    String name();
}
