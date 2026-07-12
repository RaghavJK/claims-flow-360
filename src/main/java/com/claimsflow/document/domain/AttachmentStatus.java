package com.claimsflow.document.domain;

/**
 * Lifecycle of an uploaded claim document.
 *
 * <pre>
 * PENDING_UPLOAD → UPLOADED → OCR_COMPLETE
 *                          ↘  OCR_FAILED   (retryable via re-trigger)
 * </pre>
 */
public enum AttachmentStatus {
    PENDING_UPLOAD,   // presigned URL issued; client has not confirmed upload
    UPLOADED,         // client confirmed the S3 PUT succeeded
    OCR_COMPLETE,     // Textract extracted text successfully
    OCR_FAILED        // Textract failed; manual retry possible
}
