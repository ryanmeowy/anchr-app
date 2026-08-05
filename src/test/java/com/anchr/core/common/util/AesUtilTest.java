package com.anchr.core.common.util;

import com.anchr.core.common.exception.EncryptionException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesUtilTest {

    private final AesUtil aesUtil = new AesUtil(Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));

    @Test
    void encryptShouldUseFreshNonceAndRoundTrip() {
        String first = aesUtil.encrypt("same-secret");
        String second = aesUtil.encrypt("same-secret");

        assertThat(first).isNotEqualTo(second);
        assertThat(aesUtil.decrypt(first)).isEqualTo("same-secret");
        assertThat(aesUtil.decrypt(second)).isEqualTo("same-secret");
    }

    @Test
    void decryptShouldRejectTamperedCiphertext() {
        byte[] envelope = Base64.getDecoder().decode(aesUtil.encrypt("secret"));
        envelope[envelope.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(envelope);

        assertThatThrownBy(() -> aesUtil.decrypt(tampered))
                .isInstanceOf(EncryptionException.class)
                .hasMessage("Decryption failed");
    }
}
