package com.smart.vision.core.ingestion.infrastructure.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TextParserSupportTest {

    @Test
    void decodeTextBytes_shouldHandleUtf8Bom() {
        byte[] bomUtf8 = new byte[]{
                (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'H', 'e', 'l', 'l', 'o'
        };

        String decoded = TextParserSupport.decodeTextBytes(bomUtf8);

        assertThat(decoded).isEqualTo("Hello");
    }

    @Test
    void decodeTextBytes_shouldHandleUtf16LeBom() {
        String source = "第一段\n第二段";
        byte[] utf16Le = source.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[utf16Le.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(utf16Le, 0, withBom, 2, utf16Le.length);

        String decoded = TextParserSupport.decodeTextBytes(withBom);

        assertThat(decoded).isEqualTo(source);
    }

    @Test
    void decodeTextBytes_shouldInferUtf16LeWithoutBomFromNullBytePattern() {
        String source = "Line1\nLine2\nLine3";
        byte[] utf16Le = source.getBytes(StandardCharsets.UTF_16LE);

        String decoded = TextParserSupport.decodeTextBytes(utf16Le);

        assertThat(decoded).isEqualTo(source);
    }
}
