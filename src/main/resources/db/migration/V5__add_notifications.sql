-- Week 4: multi-channel notifications with in-table dead-letter (FR-08)

CREATE TABLE notifications (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_ref    VARCHAR(32)     NOT NULL,
    channel      VARCHAR(16)     NOT NULL,   -- EMAIL | SMS | IN_APP
    recipient    VARCHAR(200)    NOT NULL,
    subject      VARCHAR(255)    NOT NULL,
    body         TEXT            NOT NULL,
    status       VARCHAR(16)     NOT NULL DEFAULT 'PENDING',  -- PENDING|SENT|FAILED|DEAD
    retry_count  INT             NOT NULL DEFAULT 0,
    created_at   DATETIME(6)     NOT NULL,
    sent_at      DATETIME(6)
);

CREATE INDEX idx_notifications_status    ON notifications (status, created_at);
CREATE INDEX idx_notifications_claim_ref ON notifications (claim_ref);
