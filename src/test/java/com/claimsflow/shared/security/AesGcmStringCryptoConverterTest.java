package com.claimsflow.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmStringCryptoConverterTest {

    // base64 of 32 ASCII bytes — same shape as the configured key
    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    AesGcmStringCryptoConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AesGcmStringCryptoConverter(KEY);
    }

    @Test
    void encryptDecryptRoundTrip() {
        String ssn = "123-45-6789";
        String encrypted = converter.convertToDatabaseColumn(ssn);

        assertThat(encrypted).isNotEqualTo(ssn);
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(ssn);
    }

    @Test
    void samePlaintextProducesDifferentCiphertexts() {
        // Random IV per encryption — deterministic ciphertext would leak equality
        String first = converter.convertToDatabaseColumn("123-45-6789");
        String second = converter.convertToDatabaseColumn("123-45-6789");

        assertThat(first).isNotEqualTo(second);
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo("123-45-6789");
        assertThat(converter.convertToEntityAttribute(second)).isEqualTo("123-45-6789");
    }

    @Test
    void nullPassesThroughBothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void decryptionWithWrongKeyFailsLoudly() {
        String encrypted = converter.convertToDatabaseColumn("123-45-6789");

        String otherKey = Base64.getEncoder()
                .encodeToString("ffffffffffffffffffffffffffffffff".getBytes());
        AesGcmStringCryptoConverter wrongKeyConverter = new AesGcmStringCryptoConverter(otherKey);

        // GCM auth tag mismatch must throw — never return garbage plaintext
        assertThatThrownBy(() -> wrongKeyConverter.convertToEntityAttribute(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeysThatAreNot32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThatThrownBy(() -> new AesGcmStringCryptoConverter(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
