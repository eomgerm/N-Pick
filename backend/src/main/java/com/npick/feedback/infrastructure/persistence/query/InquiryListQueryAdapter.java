package com.npick.feedback.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.feedback.application.query.InquiryListItem;
import com.npick.feedback.application.query.InquiryListQuery;
import com.npick.feedback.application.query.StatusCounts;

@Repository
public class InquiryListQueryAdapter implements InquiryListQuery {

    private static final String SQL = """
            SELECT f.feedback_id, f.status, f.resolution, f.created_at, se.query_text, sr.scene_id,
                   (f.comment IS NOT NULL) AS has_comment
            FROM feedback f
            JOIN search_result sr ON sr.search_result_id = f.search_result_id
            JOIN search_execution se ON se.search_execution_id = sr.search_execution_id
            WHERE (CAST(:status AS varchar) IS NULL OR f.status = CAST(:status AS varchar))
            ORDER BY f.created_at DESC
            LIMIT :size OFFSET :offset
            """;

    private final EntityManager entityManager;

    public InquiryListQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    private static final String COUNT_SQL =
            "SELECT count(*) FROM feedback WHERE (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))";

    private static final String GROUP_COUNT_SQL = "SELECT status, count(*) FROM feedback GROUP BY status";

    @Override
    @SuppressWarnings("unchecked")
    public List<InquiryListItem> findByStatus(String statusOrNull, int page, int size) {
        List<Tuple> rows = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("status", statusOrNull)
                .setParameter("size", size)
                .setParameter("offset", page * size)
                .getResultList();
        return rows.stream().map(this::toItem).toList();
    }

    @Override
    public long countByStatus(String statusOrNull) {
        Object count = entityManager
                .createNativeQuery(COUNT_SQL)
                .setParameter("status", statusOrNull)
                .getSingleResult();
        return ((Number) count).longValue();
    }

    @Override
    @SuppressWarnings("unchecked")
    public StatusCounts countGroupedByStatus() {
        List<Object[]> rows = entityManager.createNativeQuery(GROUP_COUNT_SQL).getResultList();
        long open = 0;
        long reviewing = 0;
        long closed = 0;
        for (Object[] row : rows) {
            String status = (String) row[0];
            long count = ((Number) row[1]).longValue();
            switch (status) {
                case "OPEN" -> open = count;
                case "REVIEWING" -> reviewing = count;
                case "CLOSED" -> closed = count;
                default -> {} // 알 수 없는 상태는 배지 집계에서 제외
            }
        }
        return new StatusCounts(open, reviewing, closed);
    }

    private InquiryListItem toItem(Tuple row) {
        return new InquiryListItem(
                ((Number) row.get("feedback_id")).longValue(),
                (String) row.get("status"),
                (String) row.get("resolution"),
                row.get("created_at", java.time.Instant.class),
                (String) row.get("query_text"),
                ((Number) row.get("scene_id")).longValue(),
                (Boolean) row.get("has_comment"));
    }
}
