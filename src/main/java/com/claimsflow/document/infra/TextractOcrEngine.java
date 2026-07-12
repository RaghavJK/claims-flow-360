package com.claimsflow.document.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.*;

import java.util.stream.Collectors;

/**
 * Amazon Textract OCR — synchronous {@code DetectDocumentText} against the
 * S3 object (images and single-page PDFs).
 *
 * <p>Multi-page PDFs need the async API ({@code StartDocumentTextDetection}
 * + SNS completion callback) — deliberately deferred: the sync call covers
 * the dominant case (photos of receipts/reports) with a fraction of the
 * moving parts. The {@link OcrEngine} port keeps that swap invisible to the
 * application layer.
 */
@Slf4j
@Component
@Profile("!test")
public class TextractOcrEngine implements OcrEngine {

    private final TextractClient textractClient;
    private final String bucket;

    public TextractOcrEngine(TextractClient textractClient,
                             @Value("${claimsflow.aws.s3.documents-bucket}") String bucket) {
        this.textractClient = textractClient;
        this.bucket = bucket;
    }

    @Override
    public String extractText(String s3Key) {
        DetectDocumentTextResponse response = textractClient.detectDocumentText(
                DetectDocumentTextRequest.builder()
                        .document(Document.builder()
                                .s3Object(S3Object.builder().bucket(bucket).name(s3Key).build())
                                .build())
                        .build());

        String text = response.blocks().stream()
                .filter(b -> b.blockType() == BlockType.LINE)
                .map(Block::text)
                .collect(Collectors.joining("\n"));

        log.info("Textract extracted {} lines from s3://{}/{}",
                response.blocks().size(), bucket, s3Key);
        return text;
    }
}
