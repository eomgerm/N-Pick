package com.npick.tag.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.tag.application.TagCorrectionAction;
import com.npick.tag.application.TagScope;
import com.npick.tag.application.query.FindPendingTagCorrectionCandidatesQueryPort;
import com.npick.tag.application.query.PendingTagCorrectionCandidate;

/**
 * 이 신고가 만든 대기 중인 검수자 태그 근거를 읽는다 (S15P21A501-317). 쓰기측({@code TagCorrectionCandidateRepositoryAdapter})과 같은 조건 —
 * {@code source='reviewer_feedback'}·{@code confirmed=false}·{@code source_feedback_id} — 으로 좁힌다.
 */
@Repository
public class PendingTagCorrectionCandidateQueryAdapter implements FindPendingTagCorrectionCandidatesQueryPort {

    // 검수자 작업이 아닌 판단(unverified)은 이 경로로 저장되지 않지만, 작업으로 되돌릴 수 없는 값이 섞여 null action 이 나가지 않게 어휘로 한 번 더 좁힌다.
    private static final String SQL = """
            SELECT te.evidence_id, tg.tagging_id, te.verification_status, tg.scene_id,
                   t.tag_type, t.match_value, t.name AS display_name
            FROM tag_evidence te
            JOIN tagging tg ON tg.tagging_id = te.tagging_id
            JOIN tag t ON t.tag_id = tg.tag_id
            WHERE te.source_feedback_id = :feedbackId
              AND te.source = 'reviewer_feedback'
              AND te.confirmed = false
              AND te.verification_status IN ('verified', 'rejected', 'withdrawn')
            ORDER BY te.created_at, te.evidence_id
            """;

    private final EntityManager entityManager;

    public PendingTagCorrectionCandidateQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PendingTagCorrectionCandidate> findPending(long sourceFeedbackId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("feedbackId", sourceFeedbackId)
                .getResultList();
        return rows.stream().map(this::toCandidate).toList();
    }

    private PendingTagCorrectionCandidate toCandidate(Tuple row) {
        return new PendingTagCorrectionCandidate(
                ((Number) row.get("evidence_id")).longValue(),
                ((Number) row.get("tagging_id")).longValue(),
                TagCorrectionAction.fromVerificationStatus((String) row.get("verification_status")),
                row.get("scene_id") == null ? TagScope.CLIP : TagScope.SCENE,
                (String) row.get("tag_type"),
                (String) row.get("match_value"),
                (String) row.get("display_name"));
    }
}
