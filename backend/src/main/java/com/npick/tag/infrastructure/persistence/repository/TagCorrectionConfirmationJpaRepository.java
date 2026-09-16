package com.npick.tag.infrastructure.persistence.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.npick.clip.infrastructure.persistence.entity.TagEvidenceJpaEntity;

/**
 * 태그 교정 후보 확정 쓰기(S15P21A501-84). 확정은 {@code tag_evidence.confirmed} 한 칸만 올리므로 native {@code UPDATE} 로 쓴다.
 *
 * <p>{@code source_feedback_id} 로 한 번 더 좁혀 다른 신고의 근거가 섞여 들어오지 못하게 하고, {@code confirmed = false} 조건으로 이미 확정된 행을 건드리지
 * 않는다(멱등). 확정 대상은 검증 실행이 승인한 소수 근거라 {@code IN} 의 바인딩 파라미터 상한(65535)은 문제되지 않는다.
 */
public interface TagCorrectionConfirmationJpaRepository extends JpaRepository<TagEvidenceJpaEntity, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE tag_evidence SET confirmed = true "
                    + "WHERE source_feedback_id = :feedbackId AND evidence_id IN (:evidenceIds) AND confirmed = false",
            nativeQuery = true)
    int confirm(
            @Param("feedbackId") long feedbackId, @Param("evidenceIds") Collection<Long> evidenceIds);
}
