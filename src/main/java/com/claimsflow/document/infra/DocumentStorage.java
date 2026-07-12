package com.claimsflow.document.infra;

import java.net.URL;

/**
 * Port for the document blob store.
 *
 * <p>The application never proxies file bytes — clients upload directly to
 * storage via a presigned URL, keeping large payloads off the API tier.
 */
public interface DocumentStorage {

    /**
     * Returns a time-limited URL the client can HTTP PUT the file to.
     */
    URL presignUpload(String s3Key, String contentType);
}
