package com.claimsflow.document.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClaimAttachmentRepository extends JpaRepository<ClaimAttachment, Long> {

    List<ClaimAttachment> findByClaimRefOrderByCreatedAtDesc(String claimRef);
}
