package com.claimsflow.claims.api;

import com.claimsflow.claims.api.dto.*;
import com.claimsflow.claims.application.*;
import com.claimsflow.claims.domain.Claim;
import com.claimsflow.claims.domain.ClaimStatus;
import com.claimsflow.claims.domain.ai.AiSummary;
import com.claimsflow.claims.infra.search.ClaimProjectionService;
import com.claimsflow.claims.infra.search.ClaimSearchRequest;
import com.claimsflow.claims.infra.search.ClaimSearchResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * REST API for the Claims bounded context.
 *
 * <p>Week 2 additions:
 * <ul>
 *   <li>GET  /api/v1/claims?q=&status=&fraudOnly= — OpenSearch faceted search</li>
 *   <li>POST /api/v1/claims/{ref}/summarize       — trigger Bedrock AI summarization</li>
 *   <li>POST /api/v1/claims/{ref}/project         — manually refresh OpenSearch projection</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/claims")
public class ClaimController {

    private final ClaimIngestionService ingestionService;
    private final WorkflowService workflowService;
    private final ClaimQueryService queryService;
    private final AiSummarizationService aiSummarizationService;
    private final ClaimProjectionService projectionService;

    public ClaimController(ClaimIngestionService ingestionService,
                           WorkflowService workflowService,
                           ClaimQueryService queryService,
                           AiSummarizationService aiSummarizationService,
                           ClaimProjectionService projectionService) {
        this.ingestionService = ingestionService;
        this.workflowService = workflowService;
        this.queryService = queryService;
        this.aiSummarizationService = aiSummarizationService;
        this.projectionService = projectionService;
    }

    // ─── Week 1 endpoints ────────────────────────────────────────────────────

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

    // ─── Week 2 endpoints ────────────────────────────────────────────────────

    /**
     * Full-text + faceted search via the OpenSearch CQRS read model.
     * Results are eventually consistent (~2 s lag after writes).
     */
    @GetMapping
    public ClaimSearchResult search(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) ClaimStatus status,
                                    @RequestParam(defaultValue = "false") boolean fraudOnly,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return queryService.search(new ClaimSearchRequest(q, status, fraudOnly, page, size));
    }

    /**
     * Trigger Bedrock Claude summarization for a specific claim.
     * Stores the result in ai_summaries and refreshes the OpenSearch projection.
     */
    @PostMapping("/{claimRef}/summarize")
    public AiSummaryResponse summarize(@PathVariable String claimRef,
                                        @AuthenticationPrincipal Jwt jwt) {
        AiSummary summary = aiSummarizationService.summarize(claimRef);
        return AiSummaryResponse.from(summary);
    }

    /**
     * Manually trigger an OpenSearch projection refresh for a claim.
     * Useful for replays or drift repair (the automated reconciliation job
     * handles this in production on a 6-hour schedule).
     */
    @PostMapping("/{claimRef}/project")
    public ResponseEntity<Void> project(@PathVariable String claimRef) {
        projectionService.project(claimRef);
        return ResponseEntity.accepted().build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String actorId(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "system";
    }
}
