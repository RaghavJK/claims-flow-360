package com.claimsflow.claims.domain;

import java.util.Map;
import java.util.Set;

/**
 * Claim workflow states. Allowed transitions encoded here as the single source
 * of truth used by {@link Claim#transitionTo(ClaimStatus)}.
 *
 * <p>Week 1 implements the core happy path + denial branch. The APPEAL path
 * and PAYMENT sub-state machine are deferred to later weeks.
 */
public enum ClaimStatus {
    SUBMITTED,
    UNDER_REVIEW,
    ADJUDICATION,
    APPROVED,
    DENIED;

    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = Map.of(
            SUBMITTED,    Set.of(UNDER_REVIEW, DENIED),
            UNDER_REVIEW, Set.of(ADJUDICATION, DENIED),
            ADJUDICATION, Set.of(APPROVED, DENIED),
            APPROVED,     Set.of(),
            DENIED,       Set.of()
    );

    public boolean canTransitionTo(ClaimStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
