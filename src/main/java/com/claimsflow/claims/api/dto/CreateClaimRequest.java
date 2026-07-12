package com.claimsflow.claims.api.dto;

import com.claimsflow.claims.application.ClaimIngestionService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateClaimRequest(
        @NotBlank @Size(max = 64) String policyNumber,
        @NotBlank @Size(max = 200) String claimantName,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amountClaimed,
        @Size(max = 2000) String description,
        // Optional PII — AES-256 encrypted at rest, never echoed back in any response
        @Pattern(regexp = "\\d{3}-?\\d{2}-?\\d{4}", message = "must be a valid SSN") String ssn
) {
    public ClaimIngestionService.IngestClaimCommand toCommand() {
        return new ClaimIngestionService.IngestClaimCommand(policyNumber, claimantName, amountClaimed, description, ssn);
    }
}
