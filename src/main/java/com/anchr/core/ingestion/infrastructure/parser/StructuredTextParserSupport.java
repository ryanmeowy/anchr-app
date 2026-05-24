package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.ingestion.domain.model.TextParseUnit;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared extraction helpers for office and HTML text formats.
 */
final class StructuredTextParserSupport {

    private static final int MAX_HEADING_LEVEL = 6;

    private StructuredTextParserSupport() {
    }

    static List<TextParseUnit> parseDocx(byte[] content, String sourceRefPrefix) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<TextParseUnit> units = new ArrayList<>();
            List<String> headingPath = new ArrayList<>();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = clean(paragraph.getText());
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                Integer headingLevel = resolveHeadingLevel(paragraph.getStyle());
                if (headingLevel != null) {
                    updateHeadingPath(headingPath, headingLevel, text);
                    units.add(unit(null, units.size(), text, sourceRefPrefix + "#heading=" + joinPath(headingPath)));
                    continue;
                }
                String prefix = headingPath.isEmpty() ? "" : "Heading: " + joinPath(headingPath) + "\n";
                units.add(unit(null, units.size(), prefix + text, sourceRefPrefix + "#heading=" + joinPath(headingPath)));
            }
            appendDocxTables(document, sourceRefPrefix, units);
            return units;
        }
    }

    static List<TextParseUnit> parseWorkbook(byte[] content, String sourceRefPrefix) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            List<TextParseUnit> units = new ArrayList<>();
            for (Sheet sheet : workbook) {
                List<String> headers = List.of();
                for (Row row : sheet) {
                    List<String> values = rowValues(row, formatter);
                    if (values.isEmpty()) {
                        continue;
                    }
                    if (headers.isEmpty()) {
                        headers = values;
                        units.add(unit(null, units.size(),
                                "Sheet: " + sheet.getSheetName() + "\nHeader: " + String.join(" | ", headers),
                                sourceRefPrefix + "#sheet=" + sheet.getSheetName() + ";row=" + (row.getRowNum() + 1)));
                        continue;
                    }
                    units.add(unit(null, units.size(),
                            "Sheet: " + sheet.getSheetName() + "\nRow " + (row.getRowNum() + 1) + ": "
                                    + rowText(headers, values),
                            sourceRefPrefix + "#sheet=" + sheet.getSheetName() + ";row=" + (row.getRowNum() + 1)));
                }
            }
            return units;
        }
    }

    static List<TextParseUnit> parseCsv(byte[] content, String sourceRefPrefix) throws IOException {
        String csv = TextParserSupport.decodeTextBytes(content);
        char delimiter = detectDelimiter(csv);
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).build();
        try (CSVParser parser = format.parse(new StringReader(csv))) {
            List<TextParseUnit> units = new ArrayList<>();
            List<String> headers = List.of();
            for (CSVRecord record : parser) {
                List<String> values = record.stream().map(StructuredTextParserSupport::clean).toList();
                if (values.stream().noneMatch(StringUtils::hasText)) {
                    continue;
                }
                long rowNumber = record.getRecordNumber();
                if (headers.isEmpty()) {
                    headers = values;
                    units.add(unit(null, units.size(), "CSV Header: " + String.join(" | ", headers),
                            sourceRefPrefix + "#row=" + rowNumber));
                    continue;
                }
                units.add(unit(null, units.size(), "CSV Row " + rowNumber + ": " + rowText(headers, values),
                        sourceRefPrefix + "#row=" + rowNumber));
            }
            return units;
        }
    }

    static List<TextParseUnit> parsePptx(byte[] content, String sourceRefPrefix) throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(content))) {
            List<TextParseUnit> units = new ArrayList<>();
            int slideNo = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                List<String> texts = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = clean(textShape.getText());
                        if (StringUtils.hasText(text)) {
                            texts.add(text);
                        }
                    }
                }
                if (!texts.isEmpty()) {
                    units.add(unit(slideNo, units.size(), "Slide " + slideNo + "\n" + String.join("\n", texts),
                            sourceRefPrefix + "#slide=" + slideNo));
                }
                slideNo++;
            }
            return units;
        }
    }

    static List<TextParseUnit> parseHtml(String html, String sourceRefPrefix) {
        Document document = Jsoup.parse(html, sourceRefPrefix);
        document.select("script,style,noscript,svg").remove();
        List<TextParseUnit> units = new ArrayList<>();
        String title = clean(document.title());
        if (StringUtils.hasText(title)) {
            units.add(unit(null, units.size(), "Title: " + title, sourceRefPrefix + "#title"));
        }
        List<String> headingPath = new ArrayList<>();
        for (Element element : document.select("h1,h2,h3,h4,h5,h6,p,li,pre,code,blockquote,td,th")) {
            String text = clean(element.text());
            if (!StringUtils.hasText(text)) {
                continue;
            }
            Integer headingLevel = resolveHtmlHeadingLevel(element.tagName());
            if (headingLevel != null) {
                updateHeadingPath(headingPath, headingLevel, text);
                units.add(unit(null, units.size(), text, sourceRefPrefix + "#heading=" + joinPath(headingPath)));
                continue;
            }
            String prefix = headingPath.isEmpty() ? "" : "Heading: " + joinPath(headingPath) + "\n";
            units.add(unit(null, units.size(), prefix + text, sourceRefPrefix + "#block=" + units.size()));
        }
        return units;
    }

    private static void appendDocxTables(XWPFDocument document, String sourceRefPrefix, List<TextParseUnit> units) {
        int tableNo = 1;
        for (XWPFTable table : document.getTables()) {
            int rowNo = 1;
            for (XWPFTableRow row : table.getRows()) {
                List<String> cells = new ArrayList<>();
                for (XWPFTableCell cell : row.getTableCells()) {
                    String text = clean(cell.getText());
                    if (StringUtils.hasText(text)) {
                        cells.add(text);
                    }
                }
                if (!cells.isEmpty()) {
                    units.add(unit(null, units.size(),
                            "Table " + tableNo + ", row " + rowNo + ": " + String.join(" | ", cells),
                            sourceRefPrefix + "#table=" + tableNo + ";row=" + rowNo));
                }
                rowNo++;
            }
            tableNo++;
        }
    }

    private static List<String> rowValues(Row row, DataFormatter formatter) {
        if (row == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        short lastCellNum = row.getLastCellNum();
        if (lastCellNum < 0) {
            return List.of();
        }
        for (int i = 0; i < lastCellNum; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.add(clean(cell == null ? "" : formatter.formatCellValue(cell)));
        }
        while (!values.isEmpty() && !StringUtils.hasText(values.get(values.size() - 1))) {
            values.remove(values.size() - 1);
        }
        return values;
    }

    private static String rowText(List<String> headers, List<String> values) {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String header = i < headers.size() && StringUtils.hasText(headers.get(i))
                    ? headers.get(i)
                    : "Column " + (i + 1);
            cells.add(header + ": " + value);
        }
        return String.join(" | ", cells);
    }

    private static TextParseUnit unit(Integer pageNo, Integer order, String text, String sourceRef) {
        TextParseUnit unit = new TextParseUnit(pageNo, order, text);
        unit.setSourceRef(sourceRef);
        return unit;
    }

    private static String clean(String text) {
        return TextParserSupport.normalizeLineEnding(text).replaceAll("[\\t ]+", " ").trim();
    }

    private static Integer resolveHeadingLevel(String style) {
        if (!StringUtils.hasText(style)) {
            return null;
        }
        String normalized = style.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("heading") && !normalized.startsWith("title")) {
            return null;
        }
        String digits = normalized.replaceAll("\\D+", "");
        if (!StringUtils.hasText(digits)) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_HEADING_LEVEL, Integer.parseInt(digits)));
    }

    private static Integer resolveHtmlHeadingLevel(String tagName) {
        if (!StringUtils.hasText(tagName) || !tagName.matches("h[1-6]")) {
            return null;
        }
        return Integer.parseInt(tagName.substring(1));
    }

    private static void updateHeadingPath(List<String> headingPath, int level, String value) {
        while (headingPath.size() >= level) {
            headingPath.remove(headingPath.size() - 1);
        }
        headingPath.add(value);
    }

    private static String joinPath(List<String> headingPath) {
        return String.join(" / ", headingPath);
    }

    private static char detectDelimiter(String csv) {
        String firstLine = csv == null ? "" : csv.lines().findFirst().orElse("");
        char best = ',';
        int bestCount = -1;
        for (char delimiter : new char[]{',', ';', '\t', '|'}) {
            int count = 0;
            for (int i = 0; i < firstLine.length(); i++) {
                if (firstLine.charAt(i) == delimiter) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = delimiter;
                bestCount = count;
            }
        }
        return best;
    }
}
