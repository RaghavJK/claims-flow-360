package com.claimsflow.claims.api;

import com.claimsflow.claims.api.dto.ClaimEventResponse;
import com.claimsflow.claims.api.dto.ClaimResponse;
import com.claimsflow.claims.api.dto.CreateClaimRequest;
import com.claimsflow.claims.api.dto.TransitionRequest;
import com.claimsflow.claims.application.ClaimIngestionService;
import com.claimsflow.claims.application.ClaimQueryService;
import com.claimsflow.claims.application.WorkflowService;
import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private final ClaimIngestionService ingestionService;
    private final WorkflowService workflowService;
    private final ClaimQueryService queryService;

    public ClaimController(ClaimIngestionService ingestionService,
                           WorkflowService workflowService,
                           ClaimQueryService queryService) {
        this.ingestionService = ingestionService;
        this.workflowService = workflowService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<ClaimResponse> create(@Valid @RequestBody CreateClaimRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        Claim claim = ingestionService.ingest(request.toCommand(), actorId(jwt));
        return ResponseEntity
                .created(URI.create("/api/v1/claims/" + claim.getClaimRef()))
                .body(ClaimResponse.from(claim));
    }

    @GetMapping("/{claimRef}")
    public ClaimResponse get(@PathVariable String claimRef) {
        return ClaimResponse.from(queryService.getByRef(claimRef));
    }

    @GetMapping("/{claimRef}/history")
    public List<ClaimEventResponse> history(@PathVariable String claimRef) {
        return queryService.getHistory(claimRef).stream()
                .map(ClaimEventResponse::from)
                .toList();
    }

    @PostMapping("/{claimRef}/transitions")
    public ClaimResponse transition(@PathVariable String claimRef,
                                    @Valid @RequestBody TransitionRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        Claim claim;
        if (request.target() == ClaimStatus.APPROVED && request.approvedAmount() != null) {
            claim = workflowService.approveWithAmount(claimRef, request.approvedAmount(), actorId(jwt));
        } else {
            claim = workflowService.transition(claimRef, request.target(), actorId(jwt), request.reason());
        }
        return ClaimResponse.from(claim);
    }

    private String actorId(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "system";
    }
}
