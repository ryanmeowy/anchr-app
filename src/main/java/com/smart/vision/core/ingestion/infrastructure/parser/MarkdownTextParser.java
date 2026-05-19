package com.smart.vision.core.ingestion.infrastructure.parser;

import com.smart.vision.core.ingestion.domain.model.TextAssetMetadata;
import com.smart.vision.core.ingestion.domain.model.TextParseResult;
import com.smart.vision.core.ingestion.domain.model.TextParseUnit;
import com.smart.vision.core.ingestion.domain.port.TextParserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for markdown files with lightweight syntax cleanup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class MarkdownTextParser implements TextParserPort {

    private final TextAssetContentLoader contentLoader;

    @Override
    public boolean supports(TextAssetMetadata metadata) {
        return TextParserSupport.matchesExtension(metadata, "md", "markdown")
                || TextParserSupport.matchesMimeType(metadata, "text/markdown", "text/x-markdown");
    }

    @Override
    public TextParseResult parse(TextAssetMetadata metadata) {
        byte[] content = contentLoader.load(metadata);
        String markdown = TextParserSupport.decodeTextBytes(content);
        String normalized = normalizeMarkdown(markdown);
        List<String> paragraphs = TextParserSupport.splitParagraphs(normalized);

        List<TextParseUnit> units = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            units.add(new TextParseUnit(null, i, paragraphs.get(i)));
        }
        return new TextParseResult(units, name());
    }

    @Override
    public String name() {
        return "markdown";
    }

    private String normalizeMarkdown(String markdown) {
        String normalized = TextParserSupport.normalizeLineEnding(markdown);
        if (normalized.isEmpty()) {
            return normalized;
        }
        StringBuilder sb = new StringBuilder(normalized.length());
        String[] lines = normalized.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(cleanLine(lines[i]));
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String cleanLine(String line) {
        String text = line == null ? "" : line.trim();
        text = text.replaceAll("^#{1,6}\\s+", "");
        text = text.replaceAll("^[-*+]\\s+", "");
        text = text.replaceAll("^\\d+\\.\\s+", "");
        return text;
    }
}
