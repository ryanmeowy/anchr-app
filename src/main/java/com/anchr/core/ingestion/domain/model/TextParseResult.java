package com.anchr.core.ingestion.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified parse result from text parser port.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextParseResult {

    private List<TextParseUnit> units = new ArrayList<>();

    private String parserName;

    public static TextParseResult placeholder(String parserName) {
        TextParseResult result = new TextParseResult();
        result.setParserName(parserName);
        result.setUnits(List.of());
        return result;
    }
}
