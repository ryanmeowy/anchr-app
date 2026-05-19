package com.smart.vision.core.search.application.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.vision.core.search.config.AppSearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCursorCodecTest {

    private SearchCursorCodec codec;

    @BeforeEach
    void setUp() {
        AppSearchProperties properties = new AppSearchProperties();
        properties.getPage().setCursorSecret("test-cursor-secret");
        codec = new SearchCursorCodec(new ObjectMapper(), properties);
        ReflectionTestUtils.setField(codec, "adminSecret", "");
        codec.init();
    }

    @Test
    void encodeDecode_shouldRoundTrip() {
        SearchCursorPayload payload = new SearchCursorPayload(
                "session-1",
                20,
                "fingerprint-1",
                System.currentTimeMillis() + 60_000
        );

        String cursor = codec.encode(payload);
        SearchCursorPayload decoded = codec.decode(cursor);

        assertThat(decoded.getSessionId()).isEqualTo(payload.getSessionId());
        assertThat(decoded.getOffset()).isEqualTo(payload.getOffset());
        assertThat(decoded.getQueryFingerprint()).isEqualTo(payload.getQueryFingerprint());
        assertThat(decoded.getExpireAt()).isEqualTo(payload.getExpireAt());
    }

    @Test
    void decode_shouldFail_whenSignatureTampered() {
        SearchCursorPayload payload = new SearchCursorPayload(
                "session-2",
                10,
                "fingerprint-2",
                System.currentTimeMillis() + 60_000
        );
        String cursor = codec.encode(payload);
        int separator = cursor.indexOf('.');
        String signature = cursor.substring(separator + 1);
        String tamperedSignature = (signature.startsWith("A") ? "B" : "A") + signature.substring(1);
        String tampered = cursor.substring(0, separator + 1) + tamperedSignature;

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cursor signature mismatch");
    }
}
