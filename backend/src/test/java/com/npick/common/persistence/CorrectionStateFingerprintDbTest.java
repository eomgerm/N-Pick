package com.npick.common.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.npick.feedback.application.port.CurrentCorrectionStatePort;
import com.npick.support.NpickPostgres;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
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
        assertThat(fp).matches(
                "rules=[^;]*;tags=[^;]*;config=[^;]*;run=[^;]*;pending_rules=[^;]*;pending_tags=[^;]*");
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
}
