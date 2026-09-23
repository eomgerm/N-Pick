package com.npick.feedback.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationRunQueryAdapterTest {

    @Test
    void acceptsUnifiedCorrectionWithTagCandidates() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("eid", 1L)).thenReturn(query);
        when(query.setParameter("fid", 2L)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("""
                {"resolution":"correction","state_fingerprint":"fp",
                 "approved_evidence_ids":[21],"approved_rule_id":null,
                 "candidate_rules":[]}
                """));

        assertThat(new VerificationRunQueryAdapter(em).find(1L, 2L)).isPresent();
    }

    @Test
    void keepsRuleActionForUnifiedCorrection() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("eid", 1L)).thenReturn(query);
        when(query.setParameter("fid", 2L)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("""
                {"resolution":"correction","state_fingerprint":"fp",
                 "approved_evidence_ids":[],"approved_rule_id":11,
                 "approved_rule_action":"patch_parse","replaced_rule_id":4,
                 "candidate_rules":[{"approved_rule_id":11,"replaced_rule_id":4,
                                     "action":"patch_parse"}]}
                """));

        assertThat(new VerificationRunQueryAdapter(em).find(1L, 2L))
                .get()
                .extracting(run -> run.approvedRuleAction())
                .isEqualTo("patch_parse");
    }

    @Test
    void rejectsReplayWithMultipleRuleCandidatesUntilConfirmSupportsAll() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("eid", 1L)).thenReturn(query);
        when(query.setParameter("fid", 2L)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("""
                {"resolution":"patch_parse","state_fingerprint":"fp",
                 "approved_rule_id":11,"replaced_rule_id":null,
                 "candidate_rules":[{"approved_rule_id":11,"replaced_rule_id":null},
                                    {"approved_rule_id":12,"replaced_rule_id":null}]}
                """));

        assertThat(new VerificationRunQueryAdapter(em).find(1L, 2L)).isEmpty();
    }
}
