package com.npick.common.persistence;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.application.port.CurrentCorrectionStatePort;
import com.npick.support.NpickPostgres;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CorrectionStateFingerprintDbTest {

    private static final long MEMBER_ID = 8301001L;
    private static final long CLIP_ID = 8301010L;
    private static final long RUN_ID = 8301020L;
    private static final long SCENE_ID = 8301030L;
    private static final long EXEC_ID = 8301040L;
    private static final long RESULT_ID = 8301050L;
    private static final long FEEDBACK_ID = 8301060L;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        NpickPostgres.datasource(registry);
    }

    @Autowired
    private CorrectionStateFingerprint fingerprint;

    @Autowired
    private CurrentCorrectionStatePort currentState; // -84 포트: 대칭 검증용

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // member, pipeline_run, clip, scene, search_execution(original), search_result, feedback 최소 그래프.
        // clip.active_pipeline_run_id = RUN_ID 로 두고, feedback → search_result → scene → clip 을 연결한다.
        TestGraph.insertReportedScene(jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
    }

    @Test
    @DisplayName("6축 정규 문자열을 rules;tags;config;run;pending_rules;pending_tags 순서로 낸다")
    void computesSixAxes() {
        String fp = fingerprint.compute(FEEDBACK_ID);
        assertThat(fp).matches("rules=[^;]*;tags=[^;]*;config=[^;]*;run=[^;]*;pending_rules=[^;]*;pending_tags=[^;]*");
        assertThat(fp).contains("config=search-config/v1:");
        assertThat(fp).contains("run=" + RUN_ID);
    }

    @Test
    @DisplayName("상태를 바꾸지 않으면 -83 기록 지문과 -84 현재 지문이 같다")
    void symmetricWithConfirmSide() {
        assertThat(fingerprint.compute(FEEDBACK_ID)).isEqualTo(currentState.currentFingerprint(FEEDBACK_ID));
    }

    @Test
    @DisplayName("run 축: 클립의 active_pipeline_run_id 가 바뀌면 지문이 달라진다")
    void runAxisChanges() {
        String before = fingerprint.compute(FEEDBACK_ID);
        // RUN_ID+1 의 pipeline_run 행이 있어야 FK 를 만족한다.
        TestGraph.insertPipelineRun(jdbc, CLIP_ID, RUN_ID + 1);
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id = ? WHERE clip_id = ?", RUN_ID + 1, CLIP_ID);
        assertThat(fingerprint.compute(FEEDBACK_ID)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("pending_rules 축: 이 신고에 새 대기 규칙 후보가 생기면 지문이 달라진다")
    void pendingRulesAxisChangesWhenCandidateAdded() {
        String before = fingerprint.compute(FEEDBACK_ID);
        TestGraph.insertPendingPatchRule(jdbc, FEEDBACK_ID, 8301070L);
        assertThat(fingerprint.compute(FEEDBACK_ID)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("pending_tags 축: 이 신고에 미확정 검수자 태그 후보가 생기면 지문이 달라진다")
    void pendingTagsAxisChangesWhenCandidateAdded() {
        String before = fingerprint.compute(FEEDBACK_ID);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, 8301080L);
        assertThat(fingerprint.compute(FEEDBACK_ID)).isNotEqualTo(before);
    }

    @Test
    @DisplayName("대기 후보가 늘지 않으면 compute 는 반복 호출에도 안정적이다")
    void computeIsStableWithoutNewPendingCandidate() {
        String first = fingerprint.compute(FEEDBACK_ID);
        String second = fingerprint.compute(FEEDBACK_ID);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("compute(feedbackId, loaded ids) 는 pending 축을 재조회가 아니라 인자로 받은 id 에서 낸다 " + "(S15P21A501-83 경합 수정)")
    void overloadDerivesPendingAxesFromLoadedIdsNotReQuery() {
        long loadedOnly = 8301071L;
        long committedAfterLoad = 8301072L;
        // "loadedOnly" 만 로드했다고 가정하고, 그 뒤 다른 후보("committedAfterLoad")가 커밋됐다고 가정한다.
        TestGraph.insertPendingPatchRule(jdbc, FEEDBACK_ID, loadedOnly);
        TestGraph.insertPendingPatchRule(jdbc, FEEDBACK_ID, committedAfterLoad);

        String fromLoadedIds = fingerprint.compute(FEEDBACK_ID, List.of(loadedOnly), List.of());

        assertThat(fromLoadedIds).contains("pending_rules=" + loadedOnly);
        assertThat(fromLoadedIds).doesNotContain(String.valueOf(committedAfterLoad));
        // 재조회 경로(compute(feedbackId))는 둘 다 잡아 로드 기반 값과 달라진다 — 드리프트 감지가 그대로 작동한다.
        assertThat(fingerprint.compute(FEEDBACK_ID)).isNotEqualTo(fromLoadedIds);
    }

    @Test
    @DisplayName("경합 없이 넘긴 id가 실제 대기 후보 전체와 같으면 오버로드 결과는 재조회(compute(feedbackId))와 같다")
    void overloadEqualsReQueryWhenLoadedIdsMatchActualPendingState() {
        // 다른 테스트가 공유 FEEDBACK_ID 에 남긴 대기 후보(순서 의존 오염)를 피하려고 이 테스트만 별도 신고를 심는다 —
        // 전체 재조회와의 완전 일치를 검사하므로 다른 테스트의 잔여 행이 섞이면 안 된다.
        long memberId = 8301091L,
                clipId = 8301092L,
                runId = 8301093L,
                sceneId = 8301094L,
                execId = 8301095L,
                resultId = 8301096L,
                feedbackId = 8301097L,
                ruleId = 8301098L,
                evidenceId = 8301099L;
        TestGraph.insertReportedScene(jdbc, memberId, clipId, runId, sceneId, execId, resultId, feedbackId);
        TestGraph.insertPendingPatchRule(jdbc, feedbackId, ruleId);
        TestGraph.insertReviewerTagCandidate(jdbc, sceneId, clipId, feedbackId, evidenceId);

        String fromLoadedIds = fingerprint.compute(feedbackId, List.of(ruleId), List.of(evidenceId));

        assertThat(fromLoadedIds).isEqualTo(fingerprint.compute(feedbackId));
    }
}
