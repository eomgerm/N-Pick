package com.npick.search.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import org.springframework.stereotype.Repository;

import com.npick.search.application.query.pending.FindPendingSearchRuleCandidatesQueryPort;
import com.npick.search.application.query.pending.PendingSearchRuleCandidates;

/**
 * 이 신고가 만든 대기 중인 규칙 후보({@code active=false})를 본문과 함께 읽는다 (S15P21A501-317). 검증(-83)의
 * {@code PendingCandidatesQueryAdapter} 와 같은 조건이다. 한 번 읽고 action 으로 나눈다.
 */
@Repository
public class PendingSearchRuleCandidatesQueryAdapter implements FindPendingSearchRuleCandidatesQueryPort {

    private static final String SQL = """
            SELECT sr.search_rule_id, sr.action, sr.target_scene_id, sr.replaces_rule_id,
                   CAST(sr.condition_json AS text) AS condition_json,
                   CAST(sr.patch_json AS text) AS patch_json
            FROM search_rule sr
            WHERE sr.source_feedback_id = :feedbackId AND sr.active = false
            ORDER BY sr.created_at, sr.search_rule_id
            """;

    private final EntityManager entityManager;

    public PendingSearchRuleCandidatesQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PendingSearchRuleCandidates findPending(long sourceFeedbackId) {
        List<Tuple> rows = entityManager
                .createNativeQuery(SQL, Tuple.class)
                .setParameter("feedbackId", sourceFeedbackId)
                .getResultList();
        List<PendingSearchRuleCandidates.ParsePatch> parsePatches = new ArrayList<>();
        List<PendingSearchRuleCandidates.SceneExclude> sceneExcludes = new ArrayList<>();
        for (Tuple row : rows) {
            long ruleId = ((Number) row.get("search_rule_id")).longValue();
            // ck_search_rule_action_shape 가 action 별 칸 모양을 보장한다(patch_parse=condition·patch,
            // exclude_scene=target_scene_id).
            switch ((String) row.get("action")) {
                case "patch_parse" ->
                    parsePatches.add(new PendingSearchRuleCandidates.ParsePatch(
                            ruleId,
                            (String) row.get("condition_json"),
                            (String) row.get("patch_json"),
                            row.get("replaces_rule_id") == null
                                    ? null
                                    : ((Number) row.get("replaces_rule_id")).longValue()));
                case "exclude_scene" ->
                    sceneExcludes.add(new PendingSearchRuleCandidates.SceneExclude(
                            ruleId, ((Number) row.get("target_scene_id")).longValue()));
                default -> {
                    // 두 action 외에는 후보 경로가 만들지 않는다. 모르는 값은 복원 대상이 아니다.
                }
            }
        }
        return new PendingSearchRuleCandidates(parsePatches, sceneExcludes);
    }
}
