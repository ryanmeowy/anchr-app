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
 * Parser for plain text files.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class PlainTextParser implements TextParserPort {

    private final TextAssetContentLoader contentLoader;

    @Override
    public boolean supports(TextAssetMetadata metadata) {
        if (TextParserSupport.matchesExtension(metadata, "md", "markdown")) {
            return false;
        }
        return TextParserSupport.matchesExtension(metadata, "txt")
                || TextParserSupport.matchesMimeType(metadata, "text/plain");
    }

    @Override
    public TextParseResult parse(TextAssetMetadata metadata) {
        byte[] content = contentLoader.load(metadata);
        String text = TextParserSupport.decodeTextBytes(content);
        List<String> paragraphs = TextParserSupport.splitParagraphs(text);

        List<TextParseUnit> units = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            units.add(new TextParseUnit(null, i, paragraphs.get(i)));
        }
        return new TextParseResult(units, name());
    }

    @Override
    public String name() {
        return "plain-text";
    }
}
