package com.claimsflow.claims.api.dto;

import com.claimsflow.claims.domain.ClaimEvent;
import com.claimsflow.claims.domain.ClaimStatus;

import java.time.Instant;

public record ClaimEventResponse(
        Long id,
        String eventType,
        ClaimStatus fromStatus,
        ClaimStatus toStatus,
        String actorId,
        String metadata,
        Instant occurredAt
) {
    public static ClaimEventResponse from(ClaimEvent e) {
        return new ClaimEventResponse(
                e.getId(),
                e.getEventType(),
                e.getFromStatus(),
                e.getToStatus(),
                e.getActorId(),
                e.getMetadata(),
                e.getOccurredAt());
    }
}
