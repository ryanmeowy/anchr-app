package com.anchr.core.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.Map;

import static com.anchr.core.common.constant.EmbeddingConstant.CHUNK_MAX_TOKENS;
import static com.anchr.core.common.constant.EmbeddingConstant.CHUNK_MIN_TOKENS;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParseRequest(
        String requestId,
        Integer contractVersion,
        String sourceRevision,
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
            Boolean useNativeChunker,
            Boolean includeEmbeddedImages
    ) {
        public static Options chunkModel() {
            return chunkModel(false);
        }

        public static Options chunkModel(boolean includeEmbeddedImages) {
            return new Options("chunks", true, true, true, true, CHUNK_MIN_TOKENS, CHUNK_MAX_TOKENS, true,
                    includeEmbeddedImages);
        }
    }

    public record Oss(
            String endpoint,
            String bucket,
            String basePath,
            String objectKeyLayout,
            Map<String, String> encryptedCredentials
    ) {}

}
