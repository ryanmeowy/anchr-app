package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.kb.domain.model.Asset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DoclingChunkMapperTest {

    private final DoclingChunkMapper mapper = new DoclingChunkMapper();

    @Test
    void toTextChunks_shouldUseAssetTypeForImageWhenResponseTypeDisagrees() {
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("scan.png")
                .fileType("IMAGE")
                .objectKey("images/scan.png")
                .build();

        Chunk result = mapper.toTextChunks(
                asset, response("pdf", "recognized text"), 1L).getFirst();

        assertEquals("recognized text", result.getOcrText());
        assertNull(result.getChunkText());
    }

    @Test
    void toTextChunks_shouldUseAssetTypeForTextWhenResponseTypeDisagrees() {
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("document.pdf")
                .fileType("PDF")
                .objectKey("documents/document.pdf")
                .build();

        Chunk result = mapper.toTextChunks(
                asset, response("image", "document text"), 1L).getFirst();

        assertEquals("document text", result.getChunkText());
        assertNull(result.getOcrText());
    }

    @Test
    void toTextChunks_shouldGenerateStableIdFromRawChunkIdAndGeneration() {
        Asset asset = asset();
        ParseResponse response = response("pdf", "document text");

        String first = mapper.toTextChunks(asset, response, 7L)
                .getFirst()
                .getSegmentId();
        String retried = mapper.toTextChunks(asset, response, 7L)
                .getFirst()
                .getSegmentId();
        String nextGeneration = mapper.toTextChunks(asset, response, 8L)
                .getFirst()
                .getSegmentId();

        assertThat(first)
                .matches("[0-9a-f]{64}")
                .isEqualTo(retried)
                .isNotEqualTo(nextGeneration);
    }

    @Test
    void toTextChunks_shouldUseNormalizedFallbackWhenChunkIdIsMissing() {
        Asset asset = asset();
        ParseResponse spaced = response(
                "pdf",
                new ParseResponse.Chunk(
                        null,
                        "text",
                        "Document text",
                        "  Document\n\ttext  ",
                        List.of(3),
                        13,
                        "source",
                        List.of(),
                        List.of()));
        ParseResponse normalized = response(
                "pdf",
                new ParseResponse.Chunk(
                        null,
                        "text",
                        "Document text",
                        "Document text",
                        List.of(3),
                        13,
                        "source",
                        List.of(),
                        List.of()));

        Chunk first = mapper.toTextChunks(asset, spaced, 2L).getFirst();
        Chunk retried = mapper.toTextChunks(asset, normalized, 2L).getFirst();

        assertThat(first.getChunkOrder()).isZero();
        assertThat(first.getSegmentId()).isEqualTo(retried.getSegmentId());
    }

    @Test
    void toTextChunks_shouldUseResponseOrderForMissingChunkIds() {
        Asset asset = asset();
        ParseResponse.Chunk first = chunk(null, "same text", 1);
        ParseResponse.Chunk second = chunk(null, "same text", 1);
        ParseResponse response = new ParseResponse(
                "request-1",
                "docling",
                "json",
                "same text",
                "pdf",
                List.of(),
                List.of(first, second),
                List.of(),
                List.of());

        List<Chunk> chunks = mapper.toTextChunks(asset, response, 4L);

        assertThat(chunks).extracting(Chunk::getChunkOrder)
                .containsExactly(0, 1);
        assertThat(chunks).extracting(Chunk::getSegmentId)
                .doesNotHaveDuplicates();
    }

    private ParseResponse response(String fileType, String textPlain) {
        return response(fileType, chunk("chunk/0", textPlain, 1));
    }

    private ParseResponse response(String fileType, ParseResponse.Chunk chunk) {
        return new ParseResponse(
                "request-1",
                "docling",
                "json",
                chunk.textPlain(),
                fileType,
                List.of(),
                List.of(chunk),
                List.of(),
                List.of());
    }

    private ParseResponse.Chunk chunk(String chunkId, String textPlain, int pageNo) {
        return new ParseResponse.Chunk(
                chunkId,
                "text",
                textPlain,
                textPlain,
                List.of(pageNo),
                textPlain.length(),
                "source",
                List.of(),
                List.of());
    }

    private Asset asset() {
        return Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("document.pdf")
                .fileType("PDF")
                .objectKey("documents/document.pdf")
                .build();
    }
}
