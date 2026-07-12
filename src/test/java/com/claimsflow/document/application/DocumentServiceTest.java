package com.claimsflow.document.application;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import com.claimsflow.document.domain.AttachmentStatus;
import com.claimsflow.document.domain.ClaimAttachment;
import com.claimsflow.document.domain.ClaimAttachmentRepository;
import com.claimsflow.document.infra.DocumentStorage;
import com.claimsflow.document.infra.OcrEngine;
import com.claimsflow.shared.exception.ClaimNotFoundException;
import com.claimsflow.shared.exception.DocumentNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock ClaimRepository claimRepository;
    @Mock ClaimAttachmentRepository attachmentRepository;
    @Mock DocumentStorage documentStorage;
    @Mock OcrEngine ocrEngine;

    @InjectMocks DocumentService service;

    @Test
    void registerCreatesAttachmentAndReturnsPresignedUrl() throws Exception {
        Claim claim = Claim.submit("CLM-1", "POL-1", "Jane", new BigDecimal("500"), null);
        when(claimRepository.findByClaimRef("CLM-1")).thenReturn(Optional.of(claim));
        when(attachmentRepository.save(any(ClaimAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        URL fakeUrl = URI.create("https://bucket.s3/upload?sig=x").toURL();
        when(documentStorage.presignUpload(anyString(), eq("application/pdf"))).thenReturn(fakeUrl);

        DocumentService.RegisteredDocument result =
                service.register("CLM-1", "hospital report.pdf", "application/pdf", 1024L, "user-1");

        assertThat(result.uploadUrl()).isEqualTo(fakeUrl);
        assertThat(result.attachment().getStatus()).isEqualTo(AttachmentStatus.PENDING_UPLOAD);
        assertThat(result.attachment().getFileName()).isEqualTo("hospital report.pdf");

        // s3Key is namespaced by claim and sanitized (space → underscore)
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentStorage).presignUpload(keyCaptor.capture(), eq("application/pdf"));
        assertThat(keyCaptor.getValue())
                .startsWith("claims/CLM-1/")
                .endsWith("hospital_report.pdf")
                .doesNotContain(" ");
    }

    @Test
    void registerThrowsWhenClaimMissing() {
        when(claimRepository.findByClaimRef("CLM-GHOST")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.register("CLM-GHOST", "a.pdf", "application/pdf", 1L, "u"))
                .isInstanceOf(ClaimNotFoundException.class);
        verifyNoInteractions(documentStorage);
    }

    @Test
    void confirmUploadMarksAttachmentUploaded() {
        ClaimAttachment attachment = ClaimAttachment.register(1L, "CLM-1", "a.pdf", "application/pdf", 1L, "claims/CLM-1/k", "u");
        when(attachmentRepository.findById(5L)).thenReturn(Optional.of(attachment));

        ClaimAttachment result = service.confirmUpload(5L);

        assertThat(result.getStatus()).isEqualTo(AttachmentStatus.UPLOADED);
    }

    @Test
    void runOcrStoresExtractedTextAndCompletes() {
        ClaimAttachment attachment = ClaimAttachment.register(1L, "CLM-1", "a.pdf", "application/pdf", 1L, "claims/CLM-1/k", "u");
        when(attachmentRepository.findById(5L)).thenReturn(Optional.of(attachment));
        when(ocrEngine.extractText("claims/CLM-1/k")).thenReturn("Total: $1,200\nDiagnosis: whiplash");

        ClaimAttachment result = service.runOcr(5L);

        assertThat(result.getStatus()).isEqualTo(AttachmentStatus.OCR_COMPLETE);
        assertThat(result.getOcrText()).contains("whiplash");
    }

    @Test
    void ocrFailureMarksAttachmentFailedWithoutThrowing() {
        ClaimAttachment attachment = ClaimAttachment.register(1L, "CLM-1", "a.pdf", "application/pdf", 1L, "claims/CLM-1/k", "u");
        when(attachmentRepository.findById(5L)).thenReturn(Optional.of(attachment));
        when(ocrEngine.extractText(anyString())).thenThrow(new RuntimeException("Textract throttled"));

        ClaimAttachment result = service.runOcr(5L);

        assertThat(result.getStatus()).isEqualTo(AttachmentStatus.OCR_FAILED);
        assertThat(result.getOcrText()).isNull();
    }

    @Test
    void unknownAttachmentThrows() {
        when(attachmentRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.runOcr(99L))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
