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
