package com.claimsflow.document.infra;

/**
 * Port for text extraction from an uploaded document.
 */
public interface OcrEngine {

    /**
     * Extracts plain text from the document at the given storage key.
     *
     * @throws RuntimeException when extraction fails (caller marks OCR_FAILED)
     */
    String extractText(String s3Key);
}
