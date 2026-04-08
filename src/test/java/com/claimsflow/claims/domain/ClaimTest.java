package com.claimsflow.claims.domain;

import com.claimsflow.shared.exception.InvalidClaimTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimTest {

    private static Claim newClaim() {
        return Claim.submit("CLM-0001", "POL-123", "Jane Doe", new BigDecimal("1000.00"), "minor collision");
    }

    @Nested
    @DisplayName("submit")
    class Submit {

        @Test
        void createsClaimInSubmittedState() {
            Claim c = newClaim();
            assertThat(c.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
            assertThat(c.getClaimRef()).isEqualTo("CLM-0001");
            assertThat(c.getAmountClaimed()).isEqualByComparingTo("1000.00");
            assertThat(c.getCreatedAt()).isNotNull();
            assertThat(c.getUpdatedAt()).isNotNull();
        }

        @Test
        void rejectsBlankClaimRef() {
            assertThatThrownBy(() -> Claim.submit(" ", "POL", "Jane", BigDecimal.ONE, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNonPositiveAmount() {
            assertThatThrownBy(() -> Claim.submit("CLM", "POL", "Jane", BigDecimal.ZERO, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Claim.submit("CLM", "POL", "Jane", new BigDecimal("-1"), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("state machine")
    class StateMachine {

        @Test
        void walksHappyPathToApproved() {
            Claim c = newClaim();
            c.transitionTo(ClaimStatus.UNDER_REVIEW);
            c.transitionTo(ClaimStatus.ADJUDICATION);
            c.transitionTo(ClaimStatus.APPROVED);
            assertThat(c.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        }

        @Test
        void canDenyFromAnyNonTerminalState() {
            Claim c = newClaim();
            c.transitionTo(ClaimStatus.DENIED);
            assertThat(c.getStatus()).isEqualTo(ClaimStatus.DENIED);
        }

        @Test
        void rejectsSkippingReview() {
            Claim c = newClaim();
            assertThatThrownBy(() -> c.transitionTo(ClaimStatus.APPROVED))
                    .isInstanceOf(InvalidClaimTransitionException.class);
        }

        @Test
        void rejectsTransitionsFromTerminalState() {
            Claim c = newClaim();
            c.transitionTo(ClaimStatus.DENIED);
            assertThatThrownBy(() -> c.transitionTo(ClaimStatus.UNDER_REVIEW))
                    .isInstanceOf(InvalidClaimTransitionException.class);
        }
    }

    @Nested
    @DisplayName("fraud score + approval")
    class FraudAndApproval {

        @Test
        void assignsValidFraudScore() {
            Claim c = newClaim();
            c.assignFraudScore(55);
            assertThat(c.getFraudScore()).isEqualTo(55);
        }

        @Test
        void rejectsOutOfRangeFraudScore() {
            Claim c = newClaim();
            assertThatThrownBy(() -> c.assignFraudScore(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> c.assignFraudScore(101)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void approveRequiresApprovedStatus() {
            Claim c = newClaim();
            assertThatThrownBy(() -> c.approve(new BigDecimal("500")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void approveSetsAmountWhenApproved() {
            Claim c = newClaim();
            c.transitionTo(ClaimStatus.UNDER_REVIEW);
            c.transitionTo(ClaimStatus.ADJUDICATION);
            c.transitionTo(ClaimStatus.APPROVED);
            c.approve(new BigDecimal("850.00"));
            assertThat(c.getAmountApproved()).isEqualByComparingTo("850.00");
        }
    }
}
