package com.npick.clip.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tag_evidence", schema = "npick")
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TagEvidenceJpaEntity {
    @Id
    @Column(name = "evidence_id", nullable = false)
    private Long evidenceId;

    @Column(name = "tagging_id", nullable = false)
    private Long taggingId;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "confidence", columnDefinition = "numeric(5,4)")
    private BigDecimal confidence;

    @Column(name = "verification_status", nullable = false, length = 32)
    private String verificationStatus;

    @Column(name = "source_ref_type", length = 32)
    private String sourceRefType;

    @Column(name = "source_ref_id")
    private Long sourceRefId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "source_feedback_id")
    private Long sourceFeedbackId;
}
