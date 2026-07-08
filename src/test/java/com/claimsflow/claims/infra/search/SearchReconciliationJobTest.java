package com.claimsflow.claims.infra.search;

import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.infra.persistence.ClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchReconciliationJobTest {

    @Mock ClaimRepository claimRepository;
    @Mock ClaimProjectionService projectionService;

    SearchReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new SearchReconciliationJob(claimRepository, projectionService, 7);
    }

    @Test
    void reprojectsEveryClaimChangedInTheLookbackWindow() {
        when(claimRepository.findByUpdatedAtAfter(any(Instant.class)))
                .thenReturn(List.of(claim("CLM-1"), claim("CLM-2"), claim("CLM-3")));

        job.reconcile();

        verify(projectionService).project("CLM-1");
        verify(projectionService).project("CLM-2");
        verify(projectionService).project("CLM-3");
    }

    @Test
    void oneFailingClaimDoesNotAbortTheSweep() {
        when(claimRepository.findByUpdatedAtAfter(any(Instant.class)))
                .thenReturn(List.of(claim("CLM-A"), claim("CLM-BAD"), claim("CLM-C")));
        // doThrow on one specific arg would trip strict-stubbing for the other
        // refs — a conditional answer keeps all three calls legal.
        doAnswer(inv -> {
            if (inv.getArgument(0).equals("CLM-BAD")) {
                throw new RuntimeException("upsert failed");
            }
            return null;
        }).when(projectionService).project(anyString());

        job.reconcile();

        // Claims after the failure are still repaired
        verify(projectionService).project("CLM-A");
        verify(projectionService).project("CLM-C");
    }

    @Test
    void noopWhenNothingChanged() {
        when(claimRepository.findByUpdatedAtAfter(any(Instant.class))).thenReturn(List.of());

        job.reconcile();

        verifyNoInteractions(projectionService);
    }

    private Claim claim(String ref) {
        return Claim.submit(ref, "POL-1", "Test User", new BigDecimal("1000"), null);
    }
}
