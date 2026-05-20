package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.model.TextParseResult;
import com.anchr.core.ingestion.domain.port.TextParserPort;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * No-op fallback parser when no concrete parser matches.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class NoopTextParser implements TextParserPort {

    @Override
    public boolean supports(TextAssetMetadata metadata) {
        return metadata != null;
    }

    @Override
    public TextParseResult parse(TextAssetMetadata metadata) {
        return TextParseResult.placeholder(name());
    }

    @Override
    public String name() {
        return "noop";
    }
}
