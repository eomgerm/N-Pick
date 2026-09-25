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
import com.npick.feedback.application.query.InquiryScene;
import com.npick.feedback.application.query.ReviewHistory;
import com.npick.feedback.application.query.SceneEvidence;

@Repository
public class InquiryDetailQueryAdapter implements InquiryDetailQuery {

    private static final String HEADER_SQL = """
            SELECT f.feedback_id, f.status, f.resolution, f.resolution_note, f.created_at, f.comment,
                   f.reviewed_by_id, f.review_started_at, f.verified_by_execution_id,
                   sr.scene_id, sc.clip_id, sr.result_rank,
                   c.title AS clip_title, sc.start_time_ms, sc.end_time_ms,
                   sc.pipeline_run_id, pr.processing_no,
                   m.name AS reviewer_name, m.login_id AS reviewer_login_id,
                   CAST(sr.explain_json AS text) AS result_explain_json,
                   se.query_text,
                   CAST(se.explicit_filters_json AS text) AS explicit_filters_json,
                   CAST(se.parsed_query_json AS text) AS parsed_query_json,
                   CAST(se.resolver_output_json AS text) AS resolver_output_json,
                   CAST(se.applied_rules_json AS text) AS applied_rules_json,
                   CAST(se.applied_excludes_json AS text) AS applied_excludes_json
            FROM feedback f
            JOIN search_result sr ON sr.search_result_id = f.search_result_id
            JOIN search_execution se ON se.search_execution_id = sr.search_execution_id
            JOIN scene sc ON sc.scene_id = sr.scene_id
            JOIN clip c ON c.clip_id = sc.clip_id
            JOIN pipeline_run pr ON pr.pipeline_run_id = sc.pipeline_run_id
            LEFT JOIN member m ON m.member_id = f.reviewed_by_id
            WHERE f.feedback_id = :feedbackId
            """;

    // 신고 장면의 태깅 + 그 장면이 속한 클립의 클립레벨 태깅(scene_id IS NULL)을 함께 조회한다.
    // 클립 태그는 장면에 상속되므로 F-09·F-10의 "당시 근거 비교"에 포함해야 한다(P2). 장면 태깅을 먼저 노출한다.
    private static final String EVIDENCE_SQL = """
            SELECT tg.tagging_id, t.tag_type, t.match_value, t.name AS tag_name,
                   COALESCE(string_agg(DISTINCT te.source, ',' ORDER BY te.source), '') AS sources,
                   CASE
                       WHEN bool_or(te.verification_status = 'verified') THEN 'verified'
                       WHEN bool_or(te.verification_status = 'unverified') THEN 'unverified'
                       -- 여기까지 안 걸리면 남은 건 검수자 판단(rejected/withdrawn)뿐이다. NULL 로 뭉개면
                       -- 반려 태그가 "기록 없음"으로 보인다(S15P21A501-235). 옛 FE current ?? next 와 같은 수준으로
                       -- 남은 상태를 그대로 넘긴다.
                       ELSE max(te.verification_status)
                   END AS verification_status,
                   CASE WHEN tg.scene_id IS NULL THEN 'CLIP' ELSE 'SCENE' END AS scope
            FROM tagging tg
            JOIN tag t ON t.tag_id = tg.tag_id
            -- 확정된 근거만 붙인다. 검수자 교정 후보(confirmed=false, S15P21A501-160)는 확정 전까지 근거 패널에 확정 근거처럼 섞이면 안 된다.
            -- TagJudgmentQueryAdapter 의 e.confirmed 필터와 같은 불변식을 이 리더에서도 지킨다(F-09 "당시 결과와 현재 태그·근거 비교").
            -- 한 tagging 에 확정 근거가 여러 건(같은 태그가 여러 키프레임 OCR 등)이면 이 조인이 1:N 이라 tagging 이
            -- 근거 수만큼 곱해진다(S15P21A501-235). tagging 단위로 묶어 출처는 모으고 검증 상태는 verified 우선으로 하나만 낸다.
            -- 내부 조인이라 확정 근거가 하나도 없는 tagging(대기 중인 검수자 후보뿐이거나, 후보 취소 뒤 근거 없이 남은 tagging)은
            -- 줄 자체를 내지 않는다(S15P21A501-317). LEFT JOIN 이면 그런 후보가 출처 [] · 상태 NULL 인 "현재 태그"로 새어 나왔다.
            -- 대기 후보는 GET .../correction-candidates 가 따로 돌려준다.
            JOIN tag_evidence te ON te.tagging_id = tg.tagging_id AND te.confirmed
            WHERE tg.scene_id = :sceneId OR (tg.scene_id IS NULL AND tg.clip_id = :clipId)
            GROUP BY tg.tagging_id, t.tag_type, t.match_value, t.name, tg.scene_id
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
                (String) row.get("explicit_filters_json"),
                (String) row.get("parsed_query_json"),
                (String) row.get("resolver_output_json"),
                (String) row.get("applied_rules_json"),
                (String) row.get("applied_excludes_json"));
        ReviewHistory history = new ReviewHistory(
                toLong(row.get("reviewed_by_id")),
                (String) row.get("reviewer_name"),
                (String) row.get("reviewer_login_id"),
                row.get("review_started_at", Instant.class),
                toLong(row.get("verified_by_execution_id")));
        return new InquiryDetail(
                ((Number) row.get("feedback_id")).longValue(),
                (String) row.get("status"),
                (String) row.get("resolution"),
                (String) row.get("resolution_note"),
                row.get("created_at", Instant.class),
                (String) row.get("comment"),
                new InquiryScene(
                        ((Number) row.get("scene_id")).longValue(),
                        ((Number) row.get("clip_id")).longValue(),
                        (String) row.get("clip_title"),
                        ((Number) row.get("start_time_ms")).longValue(),
                        ((Number) row.get("end_time_ms")).longValue(),
                        ((Number) row.get("pipeline_run_id")).longValue(),
                        ((Number) row.get("processing_no")).intValue()),
                ((Number) row.get("result_rank")).intValue(),
                (String) row.get("result_explain_json"),
                execution,
                evidence,
                history);
    }

    private SceneEvidence toEvidence(Tuple row) {
        String sources = (String) row.get("sources");
        return new SceneEvidence(
                ((Number) row.get("tagging_id")).longValue(),
                (String) row.get("tag_type"),
                (String) row.get("match_value"),
                (String) row.get("tag_name"),
                sources == null || sources.isBlank() ? List.of() : List.of(sources.split(",")),
                (String) row.get("verification_status"),
                (String) row.get("scope"));
    }

    private Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
