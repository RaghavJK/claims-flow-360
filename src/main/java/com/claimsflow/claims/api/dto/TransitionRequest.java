package com.claimsflow.claims.api.dto;

import com.claimsflow.claims.domain.ClaimStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransitionRequest(
        @NotNull ClaimStatus target,
        @Size(max = 500) String reason,
        BigDecimal approvedAmount
) {}
