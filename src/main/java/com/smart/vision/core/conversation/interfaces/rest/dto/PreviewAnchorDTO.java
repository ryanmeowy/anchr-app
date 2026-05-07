package com.smart.vision.core.conversation.interfaces.rest.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Preview anchor for locating a segment inside an asset.
 */
@Data
public class PreviewAnchorDTO implements Serializable {

    private Integer pageNo;
    private Integer chunkOrder;
    private BboxDTO bbox;
    private Integer imageWidth;
    private Integer imageHeight;

    @Data
    public static class BboxDTO implements Serializable {
        private Integer x;
        private Integer y;
        private Integer width;
        private Integer height;
        private String unit;
    }
}
