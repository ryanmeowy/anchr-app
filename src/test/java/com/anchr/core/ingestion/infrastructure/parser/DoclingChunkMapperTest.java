package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.kb.domain.model.Asset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DoclingChunkMapperTest {

    private DoclingChunkMapper mapper;

    @BeforeEach
    void setUp() {
        AtomicInteger ids = new AtomicInteger(1000);
        IdGen idGen = mock(IdGen.class);
        when(idGen.nextIdStr()).thenAnswer(
                invocation -> String.valueOf(ids.incrementAndGet()));
        mapper = new DoclingChunkMapper(idGen);
    }

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
        assertEquals("1001", result.getSegmentId());
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
    void toTextChunks_shouldGenerateOrdinaryIdsWithoutChangingLogicalFields() {
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
                .isEqualTo("1001")
                .isNotEqualTo(retried)
                .isNotEqualTo(nextGeneration);
    }

    @Test
    void toTextChunks_shouldKeepStableSourceUrlWhenObjectKeyIsMissing() {
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("remote.png")
                .fileType("IMAGE")
                .sourceUrl("https://cdn.example.test/remote.png")
                .build();

        Chunk result = mapper.toTextChunks(
                asset, response("image", "recognized text"), 1L).getFirst();

        assertThat(result.getSourceRef())
                .isEqualTo("https://cdn.example.test/remote.png");
    }

    @Test
    void toTextChunks_shouldUseResponseOrderWhenChunkIdIsMissing() {
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
        assertThat(retried.getChunkOrder()).isZero();
        assertThat(first.getSegmentId()).isNotEqualTo(retried.getSegmentId());
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

    @Test
    void documentImagesShouldDeduplicateByBlockAndUseImageObjectAsSource() {
        ParseResponse.Image first = new ParseResponse.Image(
                1, "pictures/0", "embedded/picture-0.png", "UPLOADED", 2,
                List.of(), 640, 480, "image/png", "abc", "alt", "caption",
                "context", "ocr", null);
        ParseResponse.Image duplicate = new ParseResponse.Image(
                1, "pictures/0", "embedded/duplicate.png", "UPLOADED", 2,
                List.of(), 640, 480, "image/png", "def", null, null,
                null, null, null);
        ParseResponse response = new ParseResponse(
                "request-1", "docling", "chunks", "", "pdf",
                List.of(), List.of(), List.of(first, duplicate), List.of());

        List<Chunk> images = mapper.toDocumentImageChunks(asset(), response, 9L);

        assertThat(images).singleElement().satisfies(image -> {
            assertThat(image.getSegmentType()).isEqualTo(
                    com.anchr.core.search.domain.model.SegmentType.DOCUMENT_IMAGE);
            assertThat(image.getSegmentId()).isEqualTo("1001");
            assertThat(image.getSourceRef()).isEqualTo("embedded/picture-0.png");
            assertThat(image.getChunkText()).isEqualTo("caption\nalt\ncontext");
            assertThat(image.getOcrText()).isEqualTo("ocr");
        });
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
