package com.smart.vision.core.ingestion.infrastructure.parser;

import com.smart.vision.core.common.exception.ApiError;
import com.smart.vision.core.common.exception.BusinessException;
import com.smart.vision.core.ingestion.domain.model.TextAssetMetadata;
import com.smart.vision.core.ingestion.domain.model.TextParseResult;
import com.smart.vision.core.ingestion.domain.model.TextParseUnit;
import com.smart.vision.core.ingestion.domain.port.TextParserPort;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF parser with page-level extraction.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class PdfTextParser implements TextParserPort {

    private final TextAssetContentLoader contentLoader;

    @Override
    public boolean supports(TextAssetMetadata metadata) {
        return TextParserSupport.matchesExtension(metadata, "pdf")
                || TextParserSupport.matchesMimeType(metadata, "application/pdf");
    }

    @Override
    public TextParseResult parse(TextAssetMetadata metadata) {
        byte[] content = contentLoader.load(metadata);
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<TextParseUnit> units = new ArrayList<>();
            for (int pageNo = 1; pageNo <= document.getNumberOfPages(); pageNo++) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String text = TextParserSupport.normalizeLineEnding(stripper.getText(document));
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                units.add(new TextParseUnit(pageNo, units.size(), text));
            }
            return new TextParseResult(units, name());
        } catch (IOException e) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to parse PDF asset", e);
        }
    }

    @Override
    public String name() {
        return "pdf";
    }
}
