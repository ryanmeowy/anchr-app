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
 * XLS/XLSX/CSV parser with sheet, row, and header context.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
@RequiredArgsConstructor
public class SpreadsheetTextParser implements TextParserPort {

    private final TextAssetContentLoader contentLoader;

    @Override
    public boolean supports(TextAssetMetadata metadata) {
        return TextParserSupport.matchesExtension(metadata, "xlsx", "xls", "csv")
                || TextParserSupport.matchesMimeType(metadata,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "text/csv",
                "application/csv");
    }

    @Override
    public TextParseResult parse(TextAssetMetadata metadata) {
        byte[] content = contentLoader.load(metadata);
        try {
            if (TextParserSupport.matchesExtension(metadata, "csv")
                    || TextParserSupport.matchesMimeType(metadata, "text/csv", "application/csv")) {
                return new TextParseResult(StructuredTextParserSupport.parseCsv(content, metadata.getObjectKey()), name());
            }
            return new TextParseResult(StructuredTextParserSupport.parseWorkbook(content, metadata.getObjectKey()), name());
        } catch (IOException e) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to parse spreadsheet asset", e);
        }
    }

    @Override
    public String name() {
        return "spreadsheet";
    }
}
