package com.anchr.core.common.model;

import com.anchr.core.integration.ai.ParseResponse;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BboxInfo {
    private Bbox bbox;
    private int pageNo;


    @Data
    @Builder
    public static class Bbox{
        private double l;
        private double t;
        private double r;
        private double b;
        private String coordOrigin;
    }

    public static BboxInfo convert2BboxInfo(ParseResponse.BboxInfo bboxInfo) {
        ParseResponse.Bbox parseBbox = bboxInfo.bbox();
        Bbox bbox = Bbox.builder()
                .l(parseBbox.l())
                .t(parseBbox.t())
                .r(parseBbox.r())
                .b(parseBbox.b())
                .coordOrigin(parseBbox.coordOrigin())
                .build();

        return BboxInfo.builder()
                .bbox(bbox)
                .pageNo(bboxInfo.pageNo())
                .build();
    }
}
