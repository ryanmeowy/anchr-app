package com.anchr.core.integration.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.Map;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParseRequest(
        String requestId,
        String sourceUrl,
        String fileName,
        Options options,
        Oss oss
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Options(
            String outputFormat,
            Boolean ocr,
            Boolean ocrFallback,
            Boolean tableStructure,
            Boolean validateTextQuality,
            Integer chunkMinTokens,
            Integer chunkMaxTokens,
            Boolean useNativeChunker
    ) {
        public static Options chunkModel() {
            return new Options("chunks", true, true, true, true, 200, 300, true);
        }
    }

    public record Oss(
            String endpoint,
            String bucket,
            String basePath,
            Map<String, String> encryptedCredentials
    ) {}

}
