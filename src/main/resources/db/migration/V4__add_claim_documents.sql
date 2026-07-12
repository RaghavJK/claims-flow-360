-- Week 4: claim document attachments (FR-07)
-- Binary lives in S3; only metadata + extracted OCR text live here.

CREATE TABLE claim_documents (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id      BIGINT          NOT NULL,
    claim_ref     VARCHAR(32)     NOT NULL,
    file_name     VARCHAR(255)    NOT NULL,
    content_type  VARCHAR(128)    NOT NULL,
    size_bytes    BIGINT,
    s3_key        VARCHAR(512)    NOT NULL,
    status        VARCHAR(24)     NOT NULL DEFAULT 'PENDING_UPLOAD',
    ocr_text      TEXT,
    uploaded_by   VARCHAR(128),
    created_at    DATETIME(6)     NOT NULL,
    updated_at    DATETIME(6)     NOT NULL,
    CONSTRAINT uq_claim_documents_s3_key UNIQUE (s3_key),
    CONSTRAINT fk_claim_documents_claim FOREIGN KEY (claim_id) REFERENCES claims(id)
);

CREATE INDEX idx_claim_documents_claim_ref ON claim_documents (claim_ref);
CREATE INDEX idx_claim_documents_status    ON claim_documents (status);
