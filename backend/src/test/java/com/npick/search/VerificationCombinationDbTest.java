package com.npick.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5(S15P21A501-83): 검증 규칙 집합이 실제로 「활성 − R1 + R2」 조합인지, 태그+해석 동시 교정이 최종 조합
 * 하나로 검증되는지(F-12) 확인한다.
 */
class VerificationCombinationDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8305001L, CLIP_ID = 8305010L, RUN_ID = 8305020L,
            SCENE_ID = 8305030L, EXEC_ID = 8305040L, RESULT_ID = 8305050L, FEEDBACK_ID = 8305060L,
            R1 = 8305080L, R2 = 8305081L, OTHER_ACTIVE = 8305082L;

    @Autowired
    VerifyCorrectionCandidatesUseCase useCase;

    @Test
    @DisplayName("검증 규칙 집합은 활성 − R1 + R2 이고 R2 로 재검색한다")
    void appliesActiveMinusR1PlusR2() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        jdbc.update("UPDATE npick.feedback SET resolution = 'patch_parse' WHERE feedback_id = ?", FEEDBACK_ID);
        TestGraph.insertActivePatchRule(jdbc, FEEDBACK_ID, OTHER_ACTIVE); // 관련 없는 활성 규칙
        TestGraph.insertActivePatchRule(jdbc, FEEDBACK_ID, R1);           // 교체 대상
        TestGraph.insertPendingPatchRuleReplacing(jdbc, FEEDBACK_ID, R2, R1); // R2 -> R1

        long execId = useCase.verify(FEEDBACK_ID, MEMBER_ID).executionId();

        // verification_rule_set 필드만 떼어 확인한다 — 전체 컨텍스트 blob 에는 approved_rule_id·
        // baseline_state(지문 문자열)에도 같은 숫자들이 등장해 부분 문자열 검사로는 오탐(placeholder 도 통과)한다.
        String ruleSetJson = jdbc.queryForObject(
                "SELECT (verification_context_json->'verification_rule_set')::text "
                        + "FROM npick.search_execution WHERE search_execution_id = ?",
                String.class, execId);
        // verification_rule_set 에 OTHER_ACTIVE, R2 는 있고 R1 은 없다
        assertThat(ruleSetJson).contains(String.valueOf(OTHER_ACTIVE)).contains(String.valueOf(R2))
                .doesNotContain(String.valueOf(R1));
        // 롤백으로 공유 규칙 상태 무변경
        assertThat(jdbc.queryForObject(
                "SELECT active FROM npick.search_rule WHERE search_rule_id = ?", Boolean.class, R1)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT active FROM npick.search_rule WHERE search_rule_id = ?", Boolean.class, R2)).isFalse();
    }

    @Test
    @DisplayName("태그+해석 동시 교정을 최종 조합으로 한 번에 검증한다")
    void verifiesTagAndParseTogether() {
        TestGraph.insertSearchableReportedScene(jdbc, MEMBER_ID + 1, CLIP_ID + 1, RUN_ID + 1, SCENE_ID + 1,
                EXEC_ID + 1, RESULT_ID + 1, FEEDBACK_ID + 1);
        jdbc.update("UPDATE npick.feedback SET resolution = 'patch_parse' WHERE feedback_id = ?", FEEDBACK_ID + 1);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID + 1, CLIP_ID + 1, FEEDBACK_ID + 1, 8305090L);
        TestGraph.insertPendingPatchRule(jdbc, FEEDBACK_ID + 1, 8305091L);

        // 한 번의 verify 로 두 후보가 모두 flip 되어 검색된다(예외 없이 실행 1행)
        long execId = useCase.verify(FEEDBACK_ID + 1, MEMBER_ID + 1).executionId();
        assertThat(execId).isPositive();

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM npick.search_execution WHERE replay_of_feedback_id = ?",
                Long.class, FEEDBACK_ID + 1);
        assertThat(count).isEqualTo(1L); // 두 검증이 아니라 한 조합 검증
    }
}
