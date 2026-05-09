-- Week 2: Transactional Outbox table.
-- Events written here (atomically with claim writes) are relayed to SQS
-- by OutboxRelayScheduler. This avoids 2PC / dual-write race conditions.

CREATE TABLE outbox_events (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(64)     NOT NULL,          -- e.g. 'Claim'
    aggregate_id    VARCHAR(64)     NOT NULL,          -- claimRef
    event_type      VARCHAR(64)     NOT NULL,
    payload         TEXT            NOT NULL,           -- JSON
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT | FAILED
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at         TIMESTAMP(6)    NULL,
    retry_count     INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_outbox_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
