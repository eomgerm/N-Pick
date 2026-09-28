package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.search.application.query.search.PendingCandidates;
import com.npick.search.application.query.search.PendingCandidatesPort;

/**
 * 이 신고의 대기 교정 후보를 읽는다. 태그는 tag_evidence(confirmed=false), 규칙은 search_rule(active=false). 규칙 교체는 replaces_rule_id 로
 * R1(교체 대상)을 식별한다 (S15P21A501-81 명시 컬럼).
 */
@Repository
class PendingCandidatesQueryAdapter implements PendingCandidatesPort {

    private final EntityManager em;

    PendingCandidatesQueryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PendingCandidates load(long feedbackId) {
        List<Number> tagRows = em.createNativeQuery("SELECT te.evidence_id FROM npick.tag_evidence te "
                        + "WHERE te.source_feedback_id = :fid AND te.source = 'reviewer_feedback' "
                        + "AND te.confirmed = false ORDER BY te.evidence_id")
                .setParameter("fid", feedbackId)
                .getResultList();
        List<Long> tagEvidenceIds = tagRows.stream().map(Number::longValue).toList();

        List<Object[]> ruleRows = em.createNativeQuery(
                        "SELECT sr.search_rule_id, sr.replaces_rule_id, sr.action FROM npick.search_rule sr "
                                + "WHERE sr.source_feedback_id = :fid AND sr.active = false ORDER BY sr.search_rule_id")
                .setParameter("fid", feedbackId)
                .getResultList();

        List<PendingCandidates.RuleCandidate> rules = ruleRows.stream()
                .map(rule -> new PendingCandidates.RuleCandidate(
                        ((Number) rule[0]).longValue(),
                        rule[1] == null ? null : ((Number) rule[1]).longValue(),
                        (String) rule[2]))
                .toList();

        String resolution =
                (String) em.createNativeQuery("SELECT resolution FROM npick.feedback WHERE feedback_id = :fid")
                        .setParameter("fid", feedbackId)
                        .getSingleResult();

        return new PendingCandidates(resolution, tagEvidenceIds, rules);
    }
}
