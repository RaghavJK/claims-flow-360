package com.claimsflow.document.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URL;
import java.time.Duration;

/**
 * S3-backed document storage using presigned PUT URLs.
 *
 * <p>The client uploads directly to S3 — bytes never transit the API tier,
 * which keeps the service stateless and cheap under large attachments.
 * The URL expires after a short window; the client confirms the upload via
 * the API afterwards.
 */
@Slf4j
@Component
@Profile("!test")
public class S3DocumentStorage implements DocumentStorage {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration expiry;

    public S3DocumentStorage(S3Presigner presigner,
                             @Value("${claimsflow.aws.s3.documents-bucket}") String bucket,
                             @Value("${claimsflow.aws.s3.presign-expiry-minutes:15}") long expiryMinutes) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.expiry = Duration.ofMinutes(expiryMinutes);
    }

    @Override
    public URL presignUpload(String s3Key, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .contentType(contentType)
                .build();

        URL url = presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(expiry)
                        .putObjectRequest(put)
                        .build())
                .url();

        log.debug("Presigned upload URL issued for s3://{}/{} (expires in {})", bucket, s3Key, expiry);
        return url;
    }
}
