package com.claimsflow.shared.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM field-level encryption for PII columns (SSN, DOB).
 *
 * <p>Ciphertext layout: {@code base64(iv[12] || ciphertext+tag)}. A fresh
 * random IV per encryption means identical plaintexts produce different
 * ciphertexts — equality searches on the encrypted column are impossible by
 * design (deterministic encryption would leak equality; if lookup-by-SSN is
 * ever required, add a separate keyed-HMAC blind index column).
 *
 * <p>Key is 32 bytes, base64 in {@code claimsflow.security.field-encryption-key}.
 * Local dev reads it from application.yml; production sources it from AWS
 * Secrets Manager — never from a committed file.
 *
 * <p>Spring Boot 3 registers Hibernate's {@code SpringBeanContainer}, so this
 * converter is a real Spring bean and constructor injection works.
 */
@Component
@Converter
public class AesGcmStringCryptoConverter implements AttributeConverter<String, String> {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmStringCryptoConverter(
            @Value("${claimsflow.security.field-encryption-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "field-encryption-key must be 32 bytes (AES-256) base64-encoded; got " + keyBytes.length);
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("PII field encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbValue) {
        if (dbValue == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(dbValue);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM auth tag failure = tampered or wrong-key data; never return garbage
            throw new IllegalStateException("PII field decryption failed", e);
        }
    }
}
