package com.smart.vision.core.search.interfaces.rest.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Preview anchor for locating a segment inside an asset.
 */
@Data
@Builder
public class PreviewAnchorDTO implements Serializable {

    private Integer pageNo;
    private Integer chunkOrder;
    private BboxDTO bbox;
    private Integer imageWidth;
    private Integer imageHeight;

    @Data
    @Builder
    public static class BboxDTO implements Serializable {
        private Integer x;
        private Integer y;
        private Integer width;
        private Integer height;
        private String unit;
    }
}
