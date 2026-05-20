package com.anchr.core.ingestion.infrastructure.parser;

import com.anchr.core.ingestion.domain.model.TextAssetMetadata;
import com.anchr.core.ingestion.domain.model.TextChunk;
import com.anchr.core.ingestion.domain.model.TextParseResult;
import com.anchr.core.ingestion.domain.model.TextParseUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkSplitterTest {

    @Test
    void split_shouldGenerateStableAndOrderedChunks() {
        TextChunkSplitter splitter = new TextChunkSplitter(10, 2);
        TextAssetMetadata metadata = new TextAssetMetadata();
        metadata.setAssetId("asset-1");
        metadata.setTitle("demo");
        TextParseResult parseResult = new TextParseResult(
                List.of(new TextParseUnit(1, 0, "abcdefghij1234567890")),
                "plain-text"
        );

        List<TextChunk> first = splitter.split(metadata, parseResult);
        List<TextChunk> second = splitter.split(metadata, parseResult);

        assertThat(first).hasSize(3);
        assertThat(first).extracting(TextChunk::getChunkOrder).containsExactly(0, 1, 2);
        assertThat(first).extracting(TextChunk::getSegmentId)
                .containsExactly("asset-1:1:0", "asset-1:1:1", "asset-1:1:2");
        assertThat(second).isEqualTo(first);
    }
}
