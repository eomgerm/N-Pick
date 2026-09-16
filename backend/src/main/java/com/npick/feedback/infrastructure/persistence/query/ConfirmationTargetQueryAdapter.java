package com.npick.feedback.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.feedback.application.port.ConfirmationTarget;
import com.npick.feedback.application.port.ConfirmationTargetPort;

/** 확정 전제 판정용 신고 상태를 읽는다 (S15P21A501-84). resolution·verified_by_execution_id 는 JPA 엔티티에 없는 칸이라 native 로 읽는다. */
@Repository
class ConfirmationTargetQueryAdapter implements ConfirmationTargetPort {

    private final EntityManager em;

    ConfirmationTargetQueryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<ConfirmationTarget> find(long feedbackId) {
        List<?> rows = em.createNativeQuery("SELECT status, resolution, reviewed_by_id, verified_by_execution_id "
                        + "FROM npick.feedback WHERE feedback_id = :id")
                .setParameter("id", feedbackId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        return Optional.of(new ConfirmationTarget(
                (String) row[0], (String) row[1], toLong(row[2]), toLong(row[3])));
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
