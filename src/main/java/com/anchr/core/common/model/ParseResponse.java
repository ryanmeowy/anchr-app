package com.anchr.core.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ParseResponse(
        String requestId,
        String parser,
        String format,
        String text,
        String fileType,
        List<Page> pages,
        List<Chunk> chunks,
        List<Image> images,
        List<Warning> warnings
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(Integer pageNo, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chunk(
            String chunkId,
            String type,
            String text,
            String textPlain,
            List<Integer> pageRange,
            Integer charCount,
            String source,
            List<BboxInfo> bboxes,
            List<String> headings
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(
            Integer artifactVersion,
            String blockId,
            String imageObjectKey,
            String uploadStatus,
            Integer pageNo,
            List<BboxInfo> bboxes,
            Integer imageWidth,
            Integer imageHeight,
            String mimeType,
            String contentHash,
            String alt,
            String caption,
            String contextText,
            String ocrText,
            String url
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Warning(String code, String message, String blockId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BboxInfo(int pageNo, Bbox bbox){}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bbox(double l, double t, double r, double b, @JsonProperty("coord_origin") String coordOrigin){}
}
