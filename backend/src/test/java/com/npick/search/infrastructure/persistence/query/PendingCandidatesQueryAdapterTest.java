package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PendingCandidatesQueryAdapterTest {

    @Test
    void loadsEveryRuleCandidateAndItsReplacement() {
        EntityManager em = mock(EntityManager.class);
        Query tags = mock(Query.class);
        Query rules = mock(Query.class);
        Query resolution = mock(Query.class);
        when(em.createNativeQuery(contains("FROM npick.tag_evidence te"))).thenReturn(tags);
        when(em.createNativeQuery(contains("FROM npick.search_rule sr"))).thenReturn(rules);
        when(em.createNativeQuery(contains("FROM npick.feedback WHERE"))).thenReturn(resolution);
        when(tags.setParameter("fid", 7L)).thenReturn(tags);
        when(rules.setParameter("fid", 7L)).thenReturn(rules);
        when(resolution.setParameter("fid", 7L)).thenReturn(resolution);
        when(tags.getResultList()).thenReturn(List.of());
        when(rules.getResultList())
                .thenReturn(List.of(new Object[] {11L, null}, new Object[] {12L, 4L}, new Object[] {13L, null}));
        when(resolution.getSingleResult()).thenReturn("exclude_scene");

        var candidates = new PendingCandidatesQueryAdapter(em).load(7L);

        assertThat(candidates.rules()).extracting(r -> r.approvedRuleId()).containsExactly(11L, 12L, 13L);
        assertThat(candidates.rules()).extracting(r -> r.replacedRuleId()).containsExactly(null, 4L, null);
    }
}
