package com.npick.feedback.infrastructure.persistence.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.feedback.application.query.InquiryScene;
import com.npick.feedback.application.query.MyInquiryDetail;
import com.npick.feedback.application.query.MyInquiryDetailQuery;

/**
 * 「내 문의 기록」 상세 조회 어댑터 (S15P21A501-185).
 *
 * <p>{@link MyInquiryListQueryAdapter}와 같은 JOIN(feedback→search_result→search_execution→scene→clip)에 처리
 * 사유·시각·explicit_filters_json 을 더해 단건을 조회한다. WHERE 절에 <b>문의 작성자(created_by_id)와 원 검색자 (searched_by_id)가 모두 세션 사용자</b>인
 * 조건을 걸어, 타인 소유·미존재·타인 검색 참조를 구분 없이 빈 값으로 돌려준다 (컨트롤러에서 동일한 404 로 응답). 검색자 조건이 없으면 남의 검색에 자기 명의로 만든 문의를 통해 그 검색어·필터가
 * 새어나간다(S15P21A501-185 리뷰).
 */
@Repository
public class MyInquiryDetailQueryAdapter implements MyInquiryDetailQuery {

    private static final String SQL = """
            SELECT f.feedback_id, se.search_execution_id, f.search_result_id,
                   f.created_at, f.updated_at, se.query_text, f.comment, f.status, f.resolution,
                   f.resolution_note, f.review_started_at, f.closed_at,
                   CAST(se.explicit_filters_json AS text) AS explicit_filters_json,
                   sc.scene_id, sc.clip_id, c.title AS clip_title, sc.start_time_ms, sc.end_time_ms,
                   sc.pipeline_run_id, pr.processing_no,
                   sr.result_rank,
                   CAST(sr.explain_json AS text) AS result_explain_json
            FROM feedback f
            JOIN search_result sr ON sr.search_result_id = f.search_result_id
            JOIN search_execution se ON se.search_execution_id = sr.search_execution_id
            JOIN scene sc ON sc.scene_id = sr.scene_id
            JOIN clip c ON c.clip_id = sc.clip_id
            JOIN pipeline_run pr ON pr.pipeline_run_id = sc.pipeline_run_id
            WHERE f.feedback_id = :feedbackId AND f.created_by_id = :ownerId
              AND se.searched_by_id = :ownerId
            """;

    private final EntityManager entityManager;

    MyInquiryDetailQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<MyInquiryDetail> findByOwner(long feedbackId, long ownerId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("feedbackId", feedbackId)
                .setParameter("ownerId", ownerId)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(toDetail(rows.get(0)));
    }

    private MyInquiryDetail toDetail(Tuple row) {
        InquiryScene scene = new InquiryScene(
                ((Number) row.get("scene_id")).longValue(),
                ((Number) row.get("clip_id")).longValue(),
                (String) row.get("clip_title"),
                ((Number) row.get("start_time_ms")).longValue(),
                ((Number) row.get("end_time_ms")).longValue(),
                ((Number) row.get("pipeline_run_id")).longValue(),
                ((Number) row.get("processing_no")).intValue());
        return new MyInquiryDetail(
                ((Number) row.get("feedback_id")).longValue(),
                ((Number) row.get("search_execution_id")).longValue(),
                ((Number) row.get("search_result_id")).longValue(),
                toInstant(row.get("created_at")),
                toInstant(row.get("updated_at")),
                (String) row.get("query_text"),
                (String) row.get("comment"),
                (String) row.get("status"),
                (String) row.get("resolution"),
                scene,
                ((Number) row.get("result_rank")).intValue(),
                (String) row.get("result_explain_json"),
                (String) row.get("explicit_filters_json"),
                (String) row.get("resolution_note"),
                toInstantOrNull(row.get("review_started_at")),
                toInstantOrNull(row.get("closed_at")));
    }

    private static Instant toInstantOrNull(Object value) {
        return value == null ? null : toInstant(value);
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
