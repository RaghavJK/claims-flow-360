package com.claimsflow.document.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.document.domain.ClaimAttachment;
import com.claimsflow.document.domain.ClaimAttachmentRepository;
import com.claimsflow.document.infra.DocumentStorage;
import com.claimsflow.document.infra.OcrEngine;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import com.claimsflow.shared.exception.DocumentNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.util.List;
import java.util.UUID;

/**
 * Document lifecycle for a claim (FR-07):
 *
 * <ol>
 *   <li>{@link #register} — creates metadata + returns a presigned S3 PUT URL</li>
 *   <li>client uploads the bytes directly to S3 (never through this service)</li>
 *   <li>{@link #confirmUpload} — client acknowledges the PUT succeeded</li>
 *   <li>{@link #runOcr} — Textract extraction; text stored on the attachment</li>
 * </ol>
 */
@Slf4j
@Service
public class DocumentService {

    private final ClaimRepository claimRepository;
    private final ClaimAttachmentRepository attachmentRepository;
    private final DocumentStorage documentStorage;
    private final OcrEngine ocrEngine;

    public DocumentService(ClaimRepository claimRepository,
                           ClaimAttachmentRepository attachmentRepository,
                           DocumentStorage documentStorage,
                           OcrEngine ocrEngine) {
        this.claimRepository = claimRepository;
        this.attachmentRepository = attachmentRepository;
        this.documentStorage = documentStorage;
        this.ocrEngine = ocrEngine;
    }

    /**
     * Registers a document against a claim and issues the upload URL.
     */
    @Transactional
    public RegisteredDocument register(String claimRef, String fileName, String contentType,
                                       Long sizeBytes, String actorId) {
        Claim claim = claimRepository.findByClaimRef(claimRef)
                .orElseThrow(() -> new ClaimNotFoundException(claimRef));

        String s3Key = "claims/%s/%s-%s".formatted(claimRef, UUID.randomUUID(), sanitize(fileName));
        ClaimAttachment attachment = attachmentRepository.save(
                ClaimAttachment.register(claim.getId(), claimRef, fileName, contentType, sizeBytes, s3Key, actorId));

        URL uploadUrl = documentStorage.presignUpload(s3Key, contentType);
        log.info("Document registered claimRef={} attachmentId={} s3Key={}", claimRef, attachment.getId(), s3Key);
        return new RegisteredDocument(attachment, uploadUrl);
    }

    @Transactional
    public ClaimAttachment confirmUpload(Long attachmentId) {
        ClaimAttachment attachment = find(attachmentId);
        attachment.markUploaded();
        return attachment;
    }

    /**
     * Runs OCR on an uploaded document. Failure marks the attachment
     * OCR_FAILED (re-triggerable) — it never propagates to the caller as a 500.
     */
    @Transactional
    public ClaimAttachment runOcr(Long attachmentId) {
        ClaimAttachment attachment = find(attachmentId);
        try {
            String text = ocrEngine.extractText(attachment.getS3Key());
            attachment.completeOcr(text);
            log.info("OCR complete attachmentId={} chars={}", attachmentId, text.length());
        } catch (RuntimeException ex) {
            attachment.failOcr();
            log.error("OCR failed attachmentId={} s3Key={}: {}", attachmentId, attachment.getS3Key(), ex.getMessage());
        }
        return attachment;
    }

    @Transactional(readOnly = true)
    public List<ClaimAttachment> listForClaim(String claimRef) {
        return attachmentRepository.findByClaimRefOrderByCreatedAtDesc(claimRef);
    }

    private ClaimAttachment find(Long attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new DocumentNotFoundException(attachmentId));
    }

    private String sanitize(String fileName) {
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Registration result: the stored metadata plus the one-time upload URL. */
    public record RegisteredDocument(ClaimAttachment attachment, URL uploadUrl) {}
}
