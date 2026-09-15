package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.search.application.port.ExcludeContext;
import com.npick.search.application.port.ExcludeContextPort;

/** 장면 제외 후보 전제 조회. feedback → search_result → search_execution 조인은 {@code InquiryDetailQueryAdapter} 와 같은 키를 쓴다. */
@Repository
public class ExcludeContextQueryAdapter implements ExcludeContextPort {

    private static final String SQL = """
            SELECT f.status, f.resolution, f.reviewed_by_id, sr.scene_id,
                   se.query_fingerprint, se.normalized_query,
                   CAST(se.normalized_filters_json AS text) AS normalized_filters_json,
                   se.normalization_version
            FROM feedback f
            JOIN search_result sr ON sr.search_result_id = f.search_result_id
            JOIN search_execution se ON se.search_execution_id = sr.search_execution_id
            WHERE f.feedback_id = :feedbackId
            """;

    private final EntityManager entityManager;

    public ExcludeContextQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<ExcludeContext> find(long feedbackId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("feedbackId", feedbackId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Tuple row = rows.get(0);
        return Optional.of(new ExcludeContext(
                (String) row.get("status"),
                (String) row.get("resolution"),
                row.get("reviewed_by_id") == null ? null : ((Number) row.get("reviewed_by_id")).longValue(),
                ((Number) row.get("scene_id")).longValue(),
                (String) row.get("query_fingerprint"),
                (String) row.get("normalized_query"),
                (String) row.get("normalized_filters_json"),
                (String) row.get("normalization_version")));
    }
}
