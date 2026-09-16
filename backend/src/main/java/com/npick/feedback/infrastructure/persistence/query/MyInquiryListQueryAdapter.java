package com.npick.feedback.infrastructure.persistence.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.feedback.application.query.InquiryScene;
import com.npick.feedback.application.query.MyInquiryListItem;
import com.npick.feedback.application.query.MyInquiryListQuery;

/**
 * 「내 문의 기록」 목록 조회 어댑터 (S15P21A501-185).
 *
 * <p>검수 목록 어댑터의 JOIN(feedback→search_result→search_execution→scene→clip)을 재사용하되, 상태 필터 대신
 * <b>본인 소유(created_by_id)</b> 조건을 걸고 FE 소비용 필드를 더 싣는다. 정렬은 접수 최신순(created_at DESC, feedback_id DESC)으로
 * 고정한다 — 같은 시각 동률에도 결정적 순서를 보장한다.
 */
@Repository
public class MyInquiryListQueryAdapter implements MyInquiryListQuery {

    private static final String SQL =
            """
            SELECT f.feedback_id, se.search_execution_id, f.search_result_id,
                   f.created_at, f.updated_at, se.query_text, f.comment, f.status, f.resolution,
                   sc.scene_id, sc.clip_id, c.title AS clip_title, sc.start_time_ms, sc.end_time_ms,
                   sc.pipeline_run_id, pr.processing_no
            FROM feedback f
            JOIN search_result sr ON sr.search_result_id = f.search_result_id
            JOIN search_execution se ON se.search_execution_id = sr.search_execution_id
            JOIN scene sc ON sc.scene_id = sr.scene_id
            JOIN clip c ON c.clip_id = sc.clip_id
            JOIN pipeline_run pr ON pr.pipeline_run_id = sc.pipeline_run_id
            WHERE f.created_by_id = :ownerId
            ORDER BY f.created_at DESC, f.feedback_id DESC
            LIMIT :size OFFSET :offset
            """;

    private static final String COUNT_SQL = "SELECT count(*) FROM feedback WHERE created_by_id = :ownerId";

    private final EntityManager entityManager;

    MyInquiryListQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<MyInquiryListItem> findByOwner(long ownerId, int page, int size) {
        List<Tuple> rows = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("ownerId", ownerId)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();
        return rows.stream().map(this::toItem).toList();
    }

    @Override
    public long countByOwner(long ownerId) {
        Number count = (Number) entityManager
                .createNativeQuery(COUNT_SQL)
                .setParameter("ownerId", ownerId)
                .getSingleResult();
        return count.longValue();
    }

    private MyInquiryListItem toItem(Tuple row) {
        InquiryScene scene = new InquiryScene(
                ((Number) row.get("scene_id")).longValue(),
                ((Number) row.get("clip_id")).longValue(),
                (String) row.get("clip_title"),
                ((Number) row.get("start_time_ms")).longValue(),
                ((Number) row.get("end_time_ms")).longValue(),
                ((Number) row.get("pipeline_run_id")).longValue(),
                ((Number) row.get("processing_no")).intValue());
        return new MyInquiryListItem(
                ((Number) row.get("feedback_id")).longValue(),
                ((Number) row.get("search_execution_id")).longValue(),
                ((Number) row.get("search_result_id")).longValue(),
                toInstant(row.get("created_at")),
                toInstant(row.get("updated_at")),
                (String) row.get("query_text"),
                (String) row.get("comment"),
                (String) row.get("status"),
                (String) row.get("resolution"),
                scene);
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return ((java.sql.Timestamp) value).toInstant();
    }
}
