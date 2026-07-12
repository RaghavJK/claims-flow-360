package com.claimsflow.document.api;

import com.claimsflow.document.application.DocumentService;
import com.claimsflow.document.domain.AttachmentStatus;
import com.claimsflow.document.domain.ClaimAttachment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * REST API for claim documents (FR-07).
 *
 * <p>Upload protocol: register → HTTP PUT the file to {@code uploadUrl} →
 * confirm → (optionally) trigger OCR.
 */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/claims/{claimRef}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterDocumentResponse register(@PathVariable String claimRef,
                                             @Valid @RequestBody RegisterDocumentRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        DocumentService.RegisteredDocument result = documentService.register(
                claimRef, request.fileName(), request.contentType(), request.sizeBytes(),
                jwt != null ? jwt.getSubject() : "system");
        return RegisterDocumentResponse.from(result);
    }

    @GetMapping("/claims/{claimRef}/documents")
    public List<AttachmentResponse> list(@PathVariable String claimRef) {
        return documentService.listForClaim(claimRef).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    @PostMapping("/documents/{attachmentId}/confirm")
    public AttachmentResponse confirmUpload(@PathVariable Long attachmentId) {
        return AttachmentResponse.from(documentService.confirmUpload(attachmentId));
    }

    @PostMapping("/documents/{attachmentId}/ocr")
    public AttachmentResponse runOcr(@PathVariable Long attachmentId) {
        return AttachmentResponse.from(documentService.runOcr(attachmentId));
    }

    // ─── DTOs ────────────────────────────────────────────────────────────────

    public record RegisterDocumentRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 128) String contentType,
            Long sizeBytes
    ) {}

    public record RegisterDocumentResponse(
            Long attachmentId,
            String claimRef,
            String fileName,
            AttachmentStatus status,
            String uploadUrl
    ) {
        static RegisterDocumentResponse from(DocumentService.RegisteredDocument result) {
            ClaimAttachment a = result.attachment();
            return new RegisterDocumentResponse(
                    a.getId(), a.getClaimRef(), a.getFileName(), a.getStatus(),
                    result.uploadUrl().toString());
        }
    }

    public record AttachmentResponse(
            Long attachmentId,
            String claimRef,
            String fileName,
            String contentType,
            Long sizeBytes,
            AttachmentStatus status,
            String ocrText,
            Instant createdAt,
            Instant updatedAt
    ) {
        static AttachmentResponse from(ClaimAttachment a) {
            return new AttachmentResponse(
                    a.getId(), a.getClaimRef(), a.getFileName(), a.getContentType(),
                    a.getSizeBytes(), a.getStatus(), a.getOcrText(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }
}
