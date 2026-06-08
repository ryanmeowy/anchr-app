package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.model.TextAssetType;
import com.anchr.core.ingestion.domain.model.TextParseResult;
import com.anchr.core.ingestion.domain.port.TextParserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * HTML parser for uploaded HTML files and webpage URLs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
@RequiredArgsConstructor
public class UrlHtmlTextParser implements TextParserPort {

    private final TextAssetContentLoader contentLoader;

    @Override
    public boolean supports(TextAssetMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        if (TextParserSupport.matchesExtension(metadata, "html", "htm")
                || TextParserSupport.matchesMimeType(metadata, "text/html", "application/xhtml+xml")) {
            return true;
        }
        if (!StringUtils.hasText(metadata.getSourceUrl())) {
            return false;
        }
        String extension = TextAssetType.resolveExtension(metadata.getSourceUrl());
        return !StringUtils.hasText(extension);
    }

    @Override
    public TextParseResult parse(TextAssetMetadata metadata) {
        try {
            String sourceRef = resolveSourceRef(metadata);
            String html = TextParserSupport.decodeTextBytes(contentLoader.load(metadata));
            return new TextParseResult(StructuredTextParserSupport.parseHtml(html, sourceRef), name());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to parse URL or HTML asset", e);
        }
    }

    @Override
    public String name() {
        return "url-html";
    }

    private String resolveSourceRef(TextAssetMetadata metadata) {
        if (StringUtils.hasText(metadata.getSourceUrl())) {
            return metadata.getSourceUrl().trim();
        }
        return metadata.getObjectKey();
    }
}
