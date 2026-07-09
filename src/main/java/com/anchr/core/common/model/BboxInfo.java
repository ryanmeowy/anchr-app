package com.anchr.core.common.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BboxInfo {
    private Bbox bbox;
    private int pageNo;


    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bbox{
        private double l;
        private double t;
        private double r;
        private double b;
        @JsonAlias("coord_origin")
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
