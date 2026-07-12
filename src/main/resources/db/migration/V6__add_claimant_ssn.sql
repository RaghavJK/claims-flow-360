-- Week 4: AES-256-GCM encrypted claimant SSN.
-- Column stores base64(iv || ciphertext+tag) — sized for the encrypted form,
-- not the 11-char plaintext. Random IV per write = no equality search by design.

ALTER TABLE claims ADD COLUMN claimant_ssn VARCHAR(512) NULL;
