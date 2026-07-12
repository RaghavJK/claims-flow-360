package com.claimsflow.document.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A document attached to a claim (photo, PDF, hospital report) stored in S3.
 *
 * <p>Named {@code ClaimAttachment} — not {@code ClaimDocument} — to avoid
 * colliding with the OpenSearch index document of that name in the search
 * package. The binary itself never touches the application: clients upload
 * directly to S3 via a presigned URL; only metadata and extracted OCR text
 * live in MySQL.
 */
@Entity
@Table(name = "claim_documents")
@Getter
@NoArgsConstructor
public class ClaimAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "claim_ref", nullable = false, length = 32)
    private String claimRef;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "s3_key", nullable = false, length = 512, unique = true)
    private String s3Key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AttachmentStatus status;

    @Lob
    @Column(name = "ocr_text")
    private String ocrText;

    @Column(name = "uploaded_by", length = 128)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private ClaimAttachment(Long claimId, String claimRef, String fileName,
                            String contentType, Long sizeBytes, String s3Key, String uploadedBy) {
        this.claimId = claimId;
        this.claimRef = claimRef;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.s3Key = s3Key;
        this.uploadedBy = uploadedBy;
        this.status = AttachmentStatus.PENDING_UPLOAD;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ClaimAttachment register(Long claimId, String claimRef, String fileName,
                                           String contentType, Long sizeBytes, String s3Key, String uploadedBy) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        if (s3Key == null || s3Key.isBlank()) {
            throw new IllegalArgumentException("s3Key is required");
        }
        return new ClaimAttachment(claimId, claimRef, fileName, contentType, sizeBytes, s3Key, uploadedBy);
    }

    public void markUploaded() {
        this.status = AttachmentStatus.UPLOADED;
        this.updatedAt = Instant.now();
    }

    public void completeOcr(String extractedText) {
        this.ocrText = extractedText;
        this.status = AttachmentStatus.OCR_COMPLETE;
        this.updatedAt = Instant.now();
    }

    public void failOcr() {
        this.status = AttachmentStatus.OCR_FAILED;
        this.updatedAt = Instant.now();
    }
}
