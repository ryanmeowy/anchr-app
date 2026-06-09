package com.anchr.core.ingestion.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Parsed text unit before chunking.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextParseUnit {
    private Integer pageNo;
    private Integer order;
    private String text;
    private String sourceRef;

    public TextParseUnit(Integer pageNo, Integer order, String text) {
        this.pageNo = pageNo;
        this.order = order;
        this.text = text;
    }
}
