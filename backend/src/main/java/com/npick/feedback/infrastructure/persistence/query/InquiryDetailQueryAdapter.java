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
            SELECT f.feedback_id, f.status, f.resolution, f.created_at, f.comment,
                   f.reviewed_by_id, f.review_started_at, f.verified_by_execution_id,
                   sr.scene_id,
                   se.query_text,
                   CAST(se.parsed_query_json AS text) AS parsed_query_json,
                   CAST(se.resolver_output_json AS text) AS resolver_output_json,
                   CAST(se.applied_rules_json AS text) AS applied_rules_json,
                   CAST(se.applied_excludes_json AS text) AS applied_excludes_json
            FROM feedback f
            JOIN search_result sr ON sr.search_result_id = f.search_result_id
            JOIN search_execution se ON se.search_execution_id = sr.search_execution_id
            WHERE f.feedback_id = :feedbackId
            """;

    private static final String EVIDENCE_SQL = """
            SELECT tg.tagging_id, t.name AS tag_name, te.source, te.verification_status
            FROM tagging tg
            JOIN tag t ON t.tag_id = tg.tag_id
            LEFT JOIN tag_evidence te ON te.tagging_id = tg.tagging_id
            WHERE tg.scene_id = :sceneId
            ORDER BY tg.tagging_id
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
        return Optional.of(toDetail(header, findEvidence(sceneId)));
    }

    @SuppressWarnings("unchecked")
    private List<SceneEvidence> findEvidence(long sceneId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(EVIDENCE_SQL, Tuple.class)
                .setParameter("sceneId", sceneId)
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
                row.get("created_at", Instant.class),
                (String) row.get("comment"),
                execution,
                evidence,
                history);
    }

    private SceneEvidence toEvidence(Tuple row) {
        return new SceneEvidence(
                ((Number) row.get("tagging_id")).longValue(),
                (String) row.get("tag_name"),
                (String) row.get("source"),
                (String) row.get("verification_status"));
    }

    private Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
