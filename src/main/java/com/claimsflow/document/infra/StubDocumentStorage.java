package com.claimsflow.document.infra;

import lombok.SneakyThrows;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URL;

/**
 * Test-profile storage stub — returns a deterministic fake URL so document
 * flows are testable without S3 or AWS credentials.
 */
@Component
@Profile("test")
public class StubDocumentStorage implements DocumentStorage {

    @Override
    @SneakyThrows
    public URL presignUpload(String s3Key, String contentType) {
        return URI.create("https://stub-bucket.local/" + s3Key + "?presigned=true").toURL();
    }
}
