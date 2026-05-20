package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.port.TextParserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Router that selects parser implementation by metadata.
 */
@Component
@RequiredArgsConstructor
public class TextParserRouter {

    private final List<TextParserPort> parsers;

    public Optional<TextParserPort> route(TextAssetMetadata metadata) {
        if (metadata == null || parsers == null || parsers.isEmpty()) {
            return Optional.empty();
        }
        return parsers.stream().filter(parser -> parser.supports(metadata)).findFirst();
    }
}
