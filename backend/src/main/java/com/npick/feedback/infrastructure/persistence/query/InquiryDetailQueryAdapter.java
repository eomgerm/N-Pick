package com.npick.feedback.infrastructure.persistence.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.feedback.application.query.ExecutionSnapshot;
import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.application.query.InquiryDetailQuery;
import com.npick.feedback.application.query.ReviewHistory;
import com.npick.feedback.application.query.SceneEvidence;

@Repository
public class InquiryDetailQueryAdapter implements InquiryDetailQuery {

    private static final String HEADER_SQL = """
            SELECT f.feedback_id, f.status, f.resolution, f.resolution_note, f.created_at, f.comment,
                   f.reviewed_by_id, f.review_started_at, f.verified_by_execution_id,
                   sr.scene_id, sc.clip_id,
                   CAST(sr.explain_json AS text) AS result_explain_json,
                   se.query_text,
                   CAST(se.parsed_query_json AS text) AS parsed_query_json,
                   CAST(se.resolver_output_json AS text) AS resolver_output_json,
                   CAST(se.applied_rules_json AS text) AS applied_rules_json,
                   CAST(se.applied_excludes_json AS text) AS applied_excludes_json
            FROM feedback f
            JOIN search_result sr ON sr.search_result_id = f.search_result_id
            JOIN search_execution se ON se.search_execution_id = sr.search_execution_id
            JOIN scene sc ON sc.scene_id = sr.scene_id
            WHERE f.feedback_id = :feedbackId
            """;

    // 신고 장면의 태깅 + 그 장면이 속한 클립의 클립레벨 태깅(scene_id IS NULL)을 함께 조회한다.
    // 클립 태그는 장면에 상속되므로 F-09·F-10의 "당시 근거 비교"에 포함해야 한다(P2). 장면 태깅을 먼저 노출한다.
    private static final String EVIDENCE_SQL = """
            SELECT tg.tagging_id, t.name AS tag_name, te.source, te.verification_status,
                   CASE WHEN tg.scene_id IS NULL THEN 'CLIP' ELSE 'SCENE' END AS scope
            FROM tagging tg
            JOIN tag t ON t.tag_id = tg.tag_id
            LEFT JOIN tag_evidence te ON te.tagging_id = tg.tagging_id
            WHERE tg.scene_id = :sceneId OR (tg.scene_id IS NULL AND tg.clip_id = :clipId)
            ORDER BY (tg.scene_id IS NULL), tg.tagging_id
            """;

    private final EntityManager entityManager;

    public InquiryDetailQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<InquiryDetail> findById(long feedbackId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(HEADER_SQL, Tuple.class)
                .setParameter("feedbackId", feedbackId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Tuple header = rows.get(0);
        long sceneId = ((Number) header.get("scene_id")).longValue();
        long clipId = ((Number) header.get("clip_id")).longValue();
        return Optional.of(toDetail(header, findEvidence(sceneId, clipId)));
    }

    @SuppressWarnings("unchecked")
    private List<SceneEvidence> findEvidence(long sceneId, long clipId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(EVIDENCE_SQL, Tuple.class)
                .setParameter("sceneId", sceneId)
                .setParameter("clipId", clipId)
                .getResultList();
        return rows.stream().map(this::toEvidence).toList();
    }

    private InquiryDetail toDetail(Tuple row, List<SceneEvidence> evidence) {
        ExecutionSnapshot execution = new ExecutionSnapshot(
                (String) row.get("query_text"),
                (String) row.get("parsed_query_json"),
                (String) row.get("resolver_output_json"),
                (String) row.get("applied_rules_json"),
                (String) row.get("applied_excludes_json"));
        ReviewHistory history = new ReviewHistory(
                toLong(row.get("reviewed_by_id")),
                row.get("review_started_at", Instant.class),
                toLong(row.get("verified_by_execution_id")));
        return new InquiryDetail(
                ((Number) row.get("feedback_id")).longValue(),
                (String) row.get("status"),
                (String) row.get("resolution"),
                (String) row.get("resolution_note"),
                row.get("created_at", Instant.class),
                (String) row.get("comment"),
                (String) row.get("result_explain_json"),
                execution,
                evidence,
                history);
    }

    private SceneEvidence toEvidence(Tuple row) {
        return new SceneEvidence(
                ((Number) row.get("tagging_id")).longValue(),
                (String) row.get("tag_name"),
                (String) row.get("source"),
                (String) row.get("verification_status"),
                (String) row.get("scope"));
    }

    private Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
