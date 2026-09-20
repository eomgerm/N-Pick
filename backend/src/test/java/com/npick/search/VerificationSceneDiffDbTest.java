package com.npick.search;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.npick.search.application.query.search.VerificationResult;
import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6(S15P21A501-83): 원 결과 장면 집합 로드 + diff 계산이 {@code VerificationResult} 에 실리는 배선을
 * 확인한다. 실제 진입/제외 판정과 이탈 사유 분기(제외 규칙/guard/score_drop)는
 * {@code SceneDiffTest} 순수 단위 테스트가 책임진다 — anchor-free 리졸버 스텁으로는 실 파이프라인이
 * SCENE_A→out, SCENE_B→in 을 함께 내도록 강제하기 어렵기 때문이다(task-6-brief Step 1).
 *
 * <p>여기서는 원 실행 결과에 검색 불가한 장면(SCENE_OLD, caption 없음)을 하나 더 심어 「원 집합이
 * 실제로 로드되고, 검증 결과에서 사라진 장면이 dropped 로 계산돼 응답에 실린다」만 확인한다.
 */
class VerificationSceneDiffDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8306001L, CLIP_ID = 8306010L, RUN_ID = 8306020L,
            SCENE_ID = 8306030L, SCENE_OLD = 8306031L, EXEC_ID = 8306040L, RESULT_ID = 8306050L,
            RESULT_ID_OLD = 8306051L, FEEDBACK_ID = 8306060L, EVIDENCE_ID = 8306070L;

    @Autowired
    VerifyCorrectionCandidatesUseCase useCase;

    @Test
    @DisplayName("원 결과 장면 집합을 로드해 검증 결과와 diff 하고 이탈 장면을 낸다")
    void computesDroppedSceneFromOriginalResultSet() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        insertUnsearchableOriginalResultScene();
        // Fix round 1(authz): verify()가 이제 담당 검수자·대기 후보 존재를 검사하므로 함께 심는다.
        claimAsReviewer();
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID);

        VerificationResult result = useCase.verify(FEEDBACK_ID, MEMBER_ID);

        assertThat(result.executionId()).isPositive();
        assertThat(result.verificationRuleSet()).isNotNull();
        // SCENE_OLD 는 caption/토큰이 없어 검증 재검색 후보에 오르지 못한다 — 원 결과에만 있던 장면.
        assertThat(result.dropped()).extracting(d -> d.sceneId()).contains(SCENE_OLD);
        // SCENE_ID 는 원 결과와 검증 결과 모두에 있으므로 진입도 이탈도 아니다.
        assertThat(result.entered()).extracting(e -> e.sceneId()).doesNotContain(SCENE_ID);
        assertThat(result.dropped()).extracting(d -> d.sceneId()).doesNotContain(SCENE_ID);
    }

    /** {@code TestGraph.insertReportedScene}은 reviewed_by_id 를 채우지 않는다 — claim 은 이 신고의 신경 밖(-83). */
    private void claimAsReviewer() {
        jdbc.update("UPDATE npick.feedback SET reviewed_by_id = ? WHERE feedback_id = ?", MEMBER_ID, FEEDBACK_ID);
    }

    /** 원 실행의 두 번째 결과 장면: 같은 clip/run 아래 검색 채널이 걸릴 텍스트가 전혀 없는 장면. */
    private void insertUnsearchableOriginalResultScene() {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO npick.scene(scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, "
                        + "shot_type, created_at, updated_at) VALUES (?, ?, ?, 2000, 3000, 'b_roll', ?, ?) "
                        + "ON CONFLICT DO NOTHING",
                SCENE_OLD, CLIP_ID, RUN_ID, now, now);
        jdbc.update("INSERT INTO npick.search_result(search_result_id, search_execution_id, scene_id, "
                        + "result_rank, explain_json) VALUES (?, ?, ?, 2, '{}'::jsonb) ON CONFLICT DO NOTHING",
                RESULT_ID_OLD, EXEC_ID, SCENE_OLD);
    }
}
