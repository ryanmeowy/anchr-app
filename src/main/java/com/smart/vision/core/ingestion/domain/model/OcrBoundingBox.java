package com.smart.vision.core.ingestion.domain.model;

import lombok.Builder;
import lombok.Value;

import java.util.Collection;
import java.util.Objects;

/**
 * OCR bbox in original image pixel coordinates.
 */
@Value
@Builder
public class OcrBoundingBox {

    public static final String PIXEL_UNIT = "PIXEL";

    Integer x;
    Integer y;
    Integer width;
    Integer height;
    String unit;

    public static OcrBoundingBox pixel(int x, int y, int width, int height) {
        return OcrBoundingBox.builder()
                .x(x)
                .y(y)
                .width(width)
                .height(height)
                .unit(PIXEL_UNIT)
                .build();
    }

    public static OcrBoundingBox enclosing(Collection<OcrBoundingBox> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        boolean hasBox = false;
        for (OcrBoundingBox box : boxes) {
            if (box == null || box.x == null || box.y == null || box.width == null || box.height == null) {
                continue;
            }
            minX = Math.min(minX, box.x);
            minY = Math.min(minY, box.y);
            maxX = Math.max(maxX, box.x + box.width);
            maxY = Math.max(maxY, box.y + box.height);
            hasBox = true;
        }
        return hasBox ? pixel(minX, minY, maxX - minX, maxY - minY) : null;
    }

    public boolean isValid() {
        return Objects.equals(PIXEL_UNIT, unit)
                && x != null
                && y != null
                && width != null
                && height != null
                && width > 0
                && height > 0;
    }
}
