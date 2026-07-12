package com.claimsflow.document.infra;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-profile OCR stub — deterministic text, no Textract required.
 */
@Component
@Profile("test")
public class StubOcrEngine implements OcrEngine {

    @Override
    public String extractText(String s3Key) {
        return "STUB OCR TEXT for " + s3Key;
    }
}
