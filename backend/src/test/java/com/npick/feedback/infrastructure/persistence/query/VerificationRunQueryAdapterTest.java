package com.npick.feedback.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.Test;

import com.npick.feedback.application.port.VerificationRun;

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
    void parsesReplayWithMultipleRuleCandidates() {
        // 복합 교정(질의교정 + 장면제외)을 한 번에 확정하므로, 유효한 규칙 후보가 여럿이면 모두 읽는다
        // (S15P21A501-309). 종류(action)가 유효해야 하며, 첫 규칙은 감사 컬럼용 단일 필드로도 노출한다.
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("eid", 1L)).thenReturn(query);
        when(query.setParameter("fid", 2L)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("""
                {"resolution":"correction","state_fingerprint":"fp",
                 "approved_rule_id":11,"replaced_rule_id":null,"approved_rule_action":"patch_parse",
                 "candidate_rules":[{"approved_rule_id":11,"replaced_rule_id":null,"action":"patch_parse"},
                                    {"approved_rule_id":12,"replaced_rule_id":null,"action":"exclude_scene"}]}
                """));

        var run = new VerificationRunQueryAdapter(em).find(1L, 2L);

        assertThat(run).isPresent();
        assertThat(run.get().rules())
                .extracting(VerificationRun.RuleRef::action)
                .containsExactly("patch_parse", "exclude_scene");
        assertThat(run.get().approvedRuleId()).isEqualTo(11L);
    }

    @Test
    void rejectsReplayWithMalformedRuleCandidate() {
        // candidate_rules 의 한 건이라도 종류가 없거나 잘못되면 스냅샷을 신뢰할 수 없어 거부한다.
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("eid", 1L)).thenReturn(query);
        when(query.setParameter("fid", 2L)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("""
                {"resolution":"correction","state_fingerprint":"fp",
                 "candidate_rules":[{"approved_rule_id":11,"replaced_rule_id":null,"action":"patch_parse"},
                                    {"approved_rule_id":12,"replaced_rule_id":null}]}
                """));

        assertThat(new VerificationRunQueryAdapter(em).find(1L, 2L)).isEmpty();
    }
}
