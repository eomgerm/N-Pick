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
 * diff 배선 + 기준선 검증 (S15P21A501-83, 기준선 재설계 S15P21A501-281). 진입/이탈 판정과 이탈 사유 분기(제외 규칙/guard/score_drop)는
 * {@code SceneDiffTest} 순수 단위 테스트가 책임진다 — anchor-free 리졸버 스텁으로는 실 파이프라인이 SCENE_A→out, SCENE_B→in 을 함께 내도록 강제하기 어렵기 때문이다.
 *
 * <p>-281 이후 diff 기준선은 원 신고 실행의 <b>저장된</b> 결과가 아니라 같은 순간의 <b>대조군(후보 미적용) 재검색</b>이다. 그래서 원 저장 결과에만 있고 지금은 검색되지 않는
 * 장면(SCENE_OLD)은 더 이상 이탈로 잡히지 않는다 — 대조군·실험군 어디에도 없기 때문. 이 테스트는 그 기준선 전환을 회귀로 고정하고, 두 검색에 공통인 장면은 진입/이탈이 아님을 확인한다.
 */
class VerificationSceneDiffDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8306001L,
            CLIP_ID = 8306010L,
            RUN_ID = 8306020L,
            SCENE_ID = 8306030L,
            SCENE_OLD = 8306031L,
            EXEC_ID = 8306040L,
            RESULT_ID = 8306050L,
            RESULT_ID_OLD = 8306051L,
            FEEDBACK_ID = 8306060L,
            EVIDENCE_ID = 8306070L;

    @Autowired
    VerifyCorrectionCandidatesUseCase useCase;

    @Test
    @DisplayName("diff 기준선은 저장된 원 결과가 아니라 같은 순간의 대조군이다")
    void diffsAgainstControlNotStoredOriginalResult() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        insertUnsearchableOriginalResultScene();
        // Fix round 1(authz): verify()가 이제 담당 검수자·대기 후보 존재를 검사하므로 함께 심는다.
        claimAsReviewer();
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID);

        VerificationResult result = useCase.verify(FEEDBACK_ID, MEMBER_ID);

        assertThat(result.executionId()).isPositive();
        assertThat(result.verificationRuleSet()).isNotNull();
        // SCENE_OLD 는 저장된 원 결과에만 있고 지금은 검색 불가(caption 없음) — 대조군에도 없으므로 이제 이탈이 아니다.
        // 기준선이 저장 원결과였다면 dropped 로 잡혔다. 이 단언이 기준선 전환(S15P21A501-281)을 고정한다.
        assertThat(result.dropped()).extracting(d -> d.sceneId()).doesNotContain(SCENE_OLD);
        // SCENE_ID 는 대조군·실험군 모두에 있으므로 진입도 이탈도 아니다.
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
        jdbc.update(
                "INSERT INTO npick.scene(scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, "
                        + "shot_type, created_at, updated_at) VALUES (?, ?, ?, 2000, 3000, 'b_roll', ?, ?) "
                        + "ON CONFLICT DO NOTHING",
                SCENE_OLD,
                CLIP_ID,
                RUN_ID,
                now,
                now);
        jdbc.update(
                "INSERT INTO npick.search_result(search_result_id, search_execution_id, scene_id, "
                        + "result_rank, explain_json) VALUES (?, ?, ?, 2, '{}'::jsonb) ON CONFLICT DO NOTHING",
                RESULT_ID_OLD,
                EXEC_ID,
                SCENE_OLD);
    }
}
