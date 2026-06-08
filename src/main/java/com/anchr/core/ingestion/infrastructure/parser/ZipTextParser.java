package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.exception.ApiError;
import com.anchr.core.common.exception.BusinessException;
import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.model.TextAssetType;
import com.anchr.core.ingestion.domain.model.TextParseResult;
import com.anchr.core.ingestion.domain.model.TextParseUnit;
import com.anchr.core.ingestion.domain.port.TextParserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP parser that extracts supported nested documents and records skipped files.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 70)
@RequiredArgsConstructor
public class ZipTextParser implements TextParserPort {

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_ENTRY_BYTES = 50 * 1024 * 1024;

    private final TextAssetContentLoader contentLoader;

    @Override
    public boolean supports(TextAssetMetadata metadata) {
        return TextParserSupport.matchesExtension(metadata, "zip")
                || TextParserSupport.matchesMimeType(metadata, "application/zip", "application/x-zip-compressed");
    }

    @Override
    public TextParseResult parse(TextAssetMetadata metadata) {
        byte[] content = contentLoader.load(metadata);
        try (ZipInputStream zipInputStream = new ZipInputStream(new java.io.ByteArrayInputStream(content))) {
            List<TextParseUnit> units = new ArrayList<>();
            ZipEntry entry;
            int entryCount = 0;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) {
                    units.add(skipUnit(metadata, units.size(), "ZIP entry limit exceeded.", "zip-entry-limit"));
                    break;
                }
                processEntry(metadata, entry, zipInputStream, units);
                zipInputStream.closeEntry();
            }
            return new TextParseResult(units, name());
        } catch (IOException e) {
            throw new BusinessException(ApiError.TEXT_PARSE_FAILED, "Failed to parse ZIP asset", e);
        }
    }

    @Override
    public String name() {
        return "zip";
    }

    private void processEntry(TextAssetMetadata metadata, ZipEntry entry,
                              ZipInputStream zipInputStream, List<TextParseUnit> units) throws IOException {
        String entryName = normalizeEntryName(entry.getName());
        if (entry.isDirectory() || shouldSkipSystemEntry(entryName)) {
            return;
        }
        String extension = TextAssetType.resolveExtension(entryName);
        if (!isSupportedNestedExtension(extension)) {
            units.add(skipUnit(metadata, units.size(), "Unsupported file type: " + entryName, entryName));
            return;
        }
        byte[] entryBytes = readEntry(zipInputStream);
        String sourceRef = metadata.getObjectKey() + "!/" + entryName;
        units.addAll(parseNestedEntry(entryBytes, extension, sourceRef, units.size()));
    }

    private List<TextParseUnit> parseNestedEntry(byte[] content, String extension,
                                                 String sourceRef, int baseOrder) throws IOException {
        List<TextParseUnit> parsed = switch (extension) {
            case "docx" -> StructuredTextParserSupport.parseDocx(content, sourceRef);
            case "xlsx", "xls" -> StructuredTextParserSupport.parseWorkbook(content, sourceRef);
            case "csv" -> StructuredTextParserSupport.parseCsv(content, sourceRef);
            case "html", "htm" -> StructuredTextParserSupport.parseHtml(TextParserSupport.decodeTextBytes(content), sourceRef);
            case "txt", "md", "markdown" -> parsePlainNested(content, sourceRef);
            case "pptx" -> StructuredTextParserSupport.parsePptx(content, sourceRef);
            default -> List.of();
        };
        for (int i = 0; i < parsed.size(); i++) {
            parsed.get(i).setOrder(baseOrder + i);
        }
        return parsed;
    }

    private List<TextParseUnit> parsePlainNested(byte[] content, String sourceRef) {
        List<String> paragraphs = TextParserSupport.splitParagraphs(TextParserSupport.decodeTextBytes(content));
        List<TextParseUnit> units = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            TextParseUnit unit = new TextParseUnit(null, i, paragraphs.get(i));
            unit.setSourceRef(sourceRef + "#paragraph=" + (i + 1));
            units.add(unit);
        }
        return units;
    }

    private TextParseUnit skipUnit(TextAssetMetadata metadata, int order, String reason, String entryName) {
        TextParseUnit unit = new TextParseUnit(null, order,
                "ZIP skipped file: " + entryName + "\nReason: " + reason);
        unit.setSourceRef(metadata.getObjectKey() + "!/" + entryName);
        return unit;
    }

    private byte[] readEntry(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = zipInputStream.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new IOException("ZIP entry is too large.");
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private boolean isSupportedNestedExtension(String extension) {
        return switch (extension) {
            case "docx", "xlsx", "xls", "csv", "html", "htm", "txt", "md", "markdown", "pptx" -> true;
            default -> false;
        };
    }

    private boolean shouldSkipSystemEntry(String entryName) {
        return !StringUtils.hasText(entryName)
                || entryName.startsWith("__MACOSX/")
                || entryName.endsWith("/.DS_Store")
                || ".DS_Store".equals(entryName);
    }

    private String normalizeEntryName(String entryName) {
        return entryName == null ? "" : entryName.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }
}
