-- ClaimsFlow360 initial schema (Week 1 vertical slice)
-- Only the tables required for the Week 1 scope: claims, claim_events, fraud_evaluations.
-- Remaining tables (policies, customers, claim_documents, ai_summaries, notifications) come in later weeks.

CREATE TABLE claims (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    claim_ref       VARCHAR(32)     NOT NULL,
    policy_number   VARCHAR(64)     NOT NULL,
    claimant_name   VARCHAR(200)    NOT NULL,
    amount_claimed  DECIMAL(15,2)   NOT NULL,
    amount_approved DECIMAL(15,2)   NULL,
    status          VARCHAR(32)     NOT NULL,
    description     VARCHAR(2000)   NULL,
    fraud_score     INT             NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_claims_claim_ref (claim_ref),
    KEY idx_claims_status (status),
    KEY idx_claims_policy_number (policy_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE claim_events (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    claim_id     BIGINT       NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    from_status  VARCHAR(32)  NULL,
    to_status    VARCHAR(32)  NULL,
    actor_id     VARCHAR(128) NULL,
    metadata     TEXT         NULL,
    occurred_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_claim_events_claim_id (claim_id),
    CONSTRAINT fk_claim_events_claim FOREIGN KEY (claim_id) REFERENCES claims(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fraud_evaluations (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    claim_id           BIGINT       NOT NULL,
    total_score        INT          NOT NULL,
    indicator_results  TEXT         NOT NULL,
    evaluated_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_fraud_eval_claim_id (claim_id),
    CONSTRAINT fk_fraud_eval_claim FOREIGN KEY (claim_id) REFERENCES claims(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
