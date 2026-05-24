package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.model.TextParseResult;
import com.anchr.core.ingestion.domain.port.TextParserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * PPTX parser with slide-level location.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
public class PptxTextParser implements TextParserPort {

    private final TextAssetContentLoader contentLoader;

    @Override
    public boolean supports(TextAssetMetadata metadata) {
        return TextParserSupport.matchesExtension(metadata, "pptx")
                || TextParserSupport.matchesMimeType(metadata,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Override
    public TextParseResult parse(TextAssetMetadata metadata) {
        byte[] content = contentLoader.load(metadata);
        try {
            return new TextParseResult(StructuredTextParserSupport.parsePptx(content, metadata.getObjectKey()), name());
        } catch (IOException e) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to parse PPTX asset", e);
        }
    }

    @Override
    public String name() {
        return "pptx";
    }
}
