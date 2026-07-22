package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.common.model.ParseResponse;
import com.anchr.core.common.util.IdGen;
import com.anchr.core.ingestion.domain.model.Chunk;
import com.anchr.core.kb.domain.model.Asset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DoclingChunkMapperTest {

    private final IdGen idGen = mock(IdGen.class);
    private final DoclingChunkMapper mapper = new DoclingChunkMapper(idGen);

    @Test
    void toTextChunks_shouldUseAssetTypeForImageWhenResponseTypeDisagrees() {
        when(idGen.nextIdStr()).thenReturn("segment-1");
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("scan.png")
                .fileType("IMAGE")
                .objectKey("images/scan.png")
                .build();

        Chunk result = mapper.toTextChunks(asset, response("pdf", "recognized text")).getFirst();

        assertEquals("recognized text", result.getOcrText());
        assertNull(result.getChunkText());
    }

    @Test
    void toTextChunks_shouldUseAssetTypeForTextWhenResponseTypeDisagrees() {
        when(idGen.nextIdStr()).thenReturn("segment-1");
        Asset asset = Asset.builder()
                .id("asset-1")
                .kbId("kb-1")
                .fileName("document.pdf")
                .fileType("PDF")
                .objectKey("documents/document.pdf")
                .build();

        Chunk result = mapper.toTextChunks(asset, response("image", "document text")).getFirst();

        assertEquals("document text", result.getChunkText());
        assertNull(result.getOcrText());
    }

    private ParseResponse response(String fileType, String textPlain) {
        ParseResponse.Chunk chunk = new ParseResponse.Chunk(
                "chunk/0",
                "text",
                textPlain,
                textPlain,
                List.of(1),
                textPlain.length(),
                "source",
                List.of(),
                List.of());
        return new ParseResponse(
                "request-1",
                "docling",
                "json",
                textPlain,
                fileType,
                List.of(),
                List.of(chunk),
                List.of(),
                List.of());
    }
}
