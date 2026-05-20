package com.anchr.core.search.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Image OCR anchor box in original image pixel coordinates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bbox implements Serializable {

    private Integer x;
    private Integer y;
    private Integer width;
    private Integer height;
    private String unit;
}
