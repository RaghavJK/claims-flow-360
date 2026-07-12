package com.claimsflow.shared.exception;

public class DocumentNotFoundException extends DomainException {
    public DocumentNotFoundException(Long attachmentId) {
        super("Document not found: " + attachmentId);
    }
}
