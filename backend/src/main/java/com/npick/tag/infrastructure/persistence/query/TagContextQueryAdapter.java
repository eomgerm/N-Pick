package com.npick.tag.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.tag.application.port.TagContext;
import com.npick.tag.application.port.TagContextPort;

/** 태그 교정 후보 전제 조회. feedback → search_result → scene 로 신고 장면·클립을 읽는다. */
@Repository
public class TagContextQueryAdapter implements TagContextPort {

    private static final String SQL = """
            SELECT f.status, f.resolution, f.reviewed_by_id, sr.scene_id, sc.clip_id
            FROM feedback f
            JOIN search_result sr ON sr.search_result_id = f.search_result_id
            JOIN scene sc ON sc.scene_id = sr.scene_id
            WHERE f.feedback_id = :feedbackId
            """;

    private final EntityManager entityManager;

    public TagContextQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<TagContext> find(long feedbackId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("feedbackId", feedbackId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Tuple row = rows.get(0);
        return Optional.of(new TagContext(
                (String) row.get("status"),
                (String) row.get("resolution"),
                row.get("reviewed_by_id") == null ? null : ((Number) row.get("reviewed_by_id")).longValue(),
                ((Number) row.get("scene_id")).longValue(),
                ((Number) row.get("clip_id")).longValue()));
    }
}
