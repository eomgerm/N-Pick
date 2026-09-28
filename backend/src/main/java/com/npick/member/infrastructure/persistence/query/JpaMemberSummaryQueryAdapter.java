package com.npick.member.infrastructure.persistence.query;

import java.util.Collection;
import java.util.List;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.member.application.query.MemberSummary;
import com.npick.member.application.query.MemberSummaryQueryPort;

@Repository
public class JpaMemberSummaryQueryAdapter implements MemberSummaryQueryPort {
    private final EntityManager entityManager;

    public JpaMemberSummaryQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<MemberSummary> findAllByIds(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) return List.of();
        return entityManager
                .createQuery(
                        "select new com.npick.member.application.query.MemberSummary(m.memberId, m.loginId)"
                                + " from MemberJpaEntity m where m.memberId in :ids",
                        MemberSummary.class)
                .setParameter("ids", memberIds)
                .getResultList();
    }
}
