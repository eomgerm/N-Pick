package com.npick.tag.infrastructure.persistence.repository;

import java.util.Collection;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.tag.domain.repository.TagCorrectionConfirmationRepository;

/**
 * 태그 교정 후보 확정 쓰기 어댑터(S15P21A501-84). native {@code UPDATE} 한 방으로 신고 범위의 미확정 근거를 확정한다.
 *
 * <p>{@code source_feedback_id} 로 한 번 더 좁혀 다른 신고의 근거가 섞여 들어오지 못하게 하고, {@code confirmed = false} 조건으로 이미 확정된 행을 건드리지
 * 않는다(멱등). 확정 대상은 검증 실행이 승인한 소수 근거라 {@code IN} 의 바인딩 파라미터 상한(65535)은 문제되지 않는다.
 *
 * <p><b>Spring Data 리포지터리를 두지 않는 이유는 모듈 경계다</b> (S15P21A501-201) — 사유는 {@link TagCorrectionCandidateRepositoryAdapter}
 * 와 같다. 쓰기 전 {@code flush}·쓰기 뒤 {@code clear} 는 기존 {@code @Modifying(flushAutomatically = true, clearAutomatically =
 * true)} 를 그대로 옮긴 것이다.
 */
@Repository
public class TagCorrectionConfirmationRepositoryAdapter implements TagCorrectionConfirmationRepository {

    private final EntityManager entityManager;

    public TagCorrectionConfirmationRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public int confirm(long sourceFeedbackId, Collection<Long> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return 0;
        }
        entityManager.flush();
        int confirmed = entityManager
                .createNativeQuery("UPDATE tag_evidence SET confirmed = true "
                        + "WHERE source_feedback_id = :feedbackId AND evidence_id IN (:evidenceIds) "
                        + "AND confirmed = false")
                .setParameter("feedbackId", sourceFeedbackId)
                .setParameter("evidenceIds", evidenceIds)
                .executeUpdate();
        // 확정으로 바뀐 행을 들고 있던 1차 캐시를 비운다. 남으면 같은 트랜잭션의 뒷 조회가 옛 confirmed 를 본다.
        entityManager.clear();
        return confirmed;
    }

    // no_action 종료(S15P21A501-281)는 이 신고가 만든 대기 근거를 지운다. confirmed=false 로 좁혀 이미 확정된 근거는 건드리지 않는다.
    @Override
    public int discardPending(long sourceFeedbackId) {
        entityManager.flush();
        int discarded = entityManager
                .createNativeQuery(
                        "DELETE FROM tag_evidence "
                                + "WHERE source_feedback_id = :feedbackId AND source = 'reviewer_feedback' AND confirmed = false")
                .setParameter("feedbackId", sourceFeedbackId)
                .executeUpdate();
        entityManager.clear();
        return discarded;
    }

    // 개별 취소(S15P21A501-309). discardPending 과 같은 조건에 evidence_id 만 더 좁힌다 — 다른 신고의 id 가 넘어와도 지우지 않는다.
    @Override
    public int discardPendingOne(long sourceFeedbackId, long evidenceId) {
        entityManager.flush();
        int discarded = entityManager
                .createNativeQuery("DELETE FROM tag_evidence "
                        + "WHERE evidence_id = :evidenceId AND source_feedback_id = :feedbackId "
                        + "AND source = 'reviewer_feedback' AND confirmed = false")
                .setParameter("evidenceId", evidenceId)
                .setParameter("feedbackId", sourceFeedbackId)
                .executeUpdate();
        entityManager.clear();
        return discarded;
    }
}
