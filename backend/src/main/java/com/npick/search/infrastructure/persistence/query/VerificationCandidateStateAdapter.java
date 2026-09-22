package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.search.application.query.search.PendingCandidates;
import com.npick.search.application.query.search.VerificationCandidateStatePort;

@Repository
class VerificationCandidateStateAdapter implements VerificationCandidateStatePort {

    private final EntityManager em;

    VerificationCandidateStateAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public void flip(PendingCandidates candidates) {
        if (!candidates.tagEvidenceIds().isEmpty()) {
            em.createNativeQuery("UPDATE npick.tag_evidence SET confirmed = true WHERE evidence_id IN (:ids)")
                    .setParameter("ids", candidates.tagEvidenceIds())
                    .executeUpdate();
        }
        if (!candidates.rules().isEmpty()) {
            List<Long> approvedIds = candidates.rules().stream()
                    .map(PendingCandidates.RuleCandidate::approvedRuleId)
                    .toList();
            em.createNativeQuery("UPDATE npick.search_rule SET active = true WHERE search_rule_id IN (:ids)")
                    .setParameter("ids", approvedIds)
                    .executeUpdate();
            List<Long> replacedIds = candidates.rules().stream()
                    .map(PendingCandidates.RuleCandidate::replacedRuleId)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
            if (!replacedIds.isEmpty()) {
                em.createNativeQuery("UPDATE npick.search_rule SET active = false WHERE search_rule_id IN (:ids)")
                        .setParameter("ids", replacedIds)
                        .executeUpdate();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Long> readActivePatchRuleIds() {
        List<Number> rows = em.createNativeQuery("SELECT search_rule_id FROM npick.search_rule "
                        + "WHERE action = 'patch_parse' AND active = true ORDER BY search_rule_id")
                .getResultList();
        return rows.stream().map(Number::longValue).toList();
    }
}
