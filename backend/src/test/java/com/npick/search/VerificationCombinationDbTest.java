package com.npick.search;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.npick.feedback.application.ConfirmCorrectionCommand;
import com.npick.feedback.application.ConfirmCorrectionUseCase;
import com.npick.feedback.application.port.CurrentCorrectionStatePort;
import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 5(S15P21A501-83): 검증 규칙 집합이 실제로 「활성 − R1 + R2」 조합인지, 태그+해석 동시 교정이 최종 조합 하나로 검증되는지(F-12) 확인한다. */
class VerificationCombinationDbTest extends AbstractVerificationSearchDbTest {

    /**
     * R1·OTHER_ACTIVE 는 {@code TestGraph.insertActivePatchRule} 로 jdbc 에 직접 커밋한 행이다 — {@code verify()} 내부 트랜잭션(교체 후보
     * flip → 검색 → 롤백)이 손대는 대상이 아니므로 그 롤백으로 되돌아가지 않는다. 공유 DB(NpickPostgres)에 {@code active=true} 로 남으면
     * {@code ParseRuleRepositoryAdapter.findActivePatchParseRules()} 가 전역(질의 무관) 조회라 다음에 실행되는 다른 검증 DbTest 의 실
     * {@code interpret()} 까지 오염시킨다 — {@code condition_json='{}'} 는 파싱 실패로 {@code SKIPPED_INCOMPATIBLE}(degraded 사유)이 되어
     * 그 실행의 status 가 succeeded 대신 degraded 로 뒤바뀐다. 복합 확정 테스트가 confirm 으로 활성화한 COMPOSITE_PARSE_RULE·
     * COMPOSITE_EXCLUDE_RULE 도 같은 이유로 되돌린다(S15P21A501-309).
     */
    @AfterEach
    void deactivateLeakedActiveRules() {
        jdbc.update(
                "UPDATE npick.search_rule SET active = false WHERE search_rule_id IN (?, ?, ?, ?, ?)",
                R1,
                OTHER_ACTIVE,
                MULTI_OLD_RULE,
                COMPOSITE_PARSE_RULE,
                COMPOSITE_EXCLUDE_RULE);
    }

    private static final long MEMBER_ID = 8305001L,
            CLIP_ID = 8305010L,
            RUN_ID = 8305020L,
            SCENE_ID = 8305030L,
            EXEC_ID = 8305040L,
            RESULT_ID = 8305050L,
            FEEDBACK_ID = 8305060L,
            R1 = 8305080L,
            R2 = 8305081L,
            OTHER_ACTIVE = 8305082L,
            MULTI_OLD_RULE = 8305180L,
            COMPOSITE_PARSE_RULE = 8305191L,
            COMPOSITE_EXCLUDE_RULE = 8305192L;

    @Autowired
    VerifyCorrectionCandidatesUseCase useCase;

    @Autowired
    ConfirmCorrectionUseCase confirmUseCase;

    @Autowired
    CurrentCorrectionStatePort currentCorrectionState;

    @Test
    @DisplayName("검증 규칙 집합은 활성 − R1 + R2 이고 R2 로 재검색한다")
    void appliesActiveMinusR1PlusR2() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        jdbc.update("UPDATE npick.feedback SET resolution = 'patch_parse' WHERE feedback_id = ?", FEEDBACK_ID);
        TestGraph.insertActivePatchRule(jdbc, FEEDBACK_ID, OTHER_ACTIVE); // 관련 없는 활성 규칙
        TestGraph.insertActivePatchRule(jdbc, FEEDBACK_ID, R1); // 교체 대상
        TestGraph.insertPendingPatchRuleReplacing(jdbc, FEEDBACK_ID, R2, R1); // R2 -> R1
        // Fix round 1(authz): verify()가 이제 담당 검수자·대기 후보 존재를 검사하므로 함께 심는다.
        TestGraph.claimFeedback(jdbc, FEEDBACK_ID, MEMBER_ID);

        long execId = useCase.verify(FEEDBACK_ID, MEMBER_ID).executionId();

        // verification_rule_set 필드만 떼어 확인한다 — 전체 컨텍스트 blob 에는 approved_rule_id·
        // baseline_state(지문 문자열)에도 같은 숫자들이 등장해 부분 문자열 검사로는 오탐(placeholder 도 통과)한다.
        String ruleSetJson = jdbc.queryForObject(
                "SELECT (verification_context_json->'verification_rule_set')::text "
                        + "FROM npick.search_execution WHERE search_execution_id = ?",
                String.class,
                execId);
        // verification_rule_set 에 OTHER_ACTIVE, R2 는 있고 R1 은 없다
        assertThat(ruleSetJson)
                .contains(String.valueOf(OTHER_ACTIVE))
                .contains(String.valueOf(R2))
                .doesNotContain(String.valueOf(R1));
        // 롤백으로 공유 규칙 상태 무변경
        assertThat(jdbc.queryForObject(
                        "SELECT active FROM npick.search_rule WHERE search_rule_id = ?", Boolean.class, R1))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT active FROM npick.search_rule WHERE search_rule_id = ?", Boolean.class, R2))
                .isFalse();
    }

    @Test
    @DisplayName("태그+해석 동시 교정을 최종 조합으로 한 번에 검증한다")
    void verifiesTagAndParseTogether() {
        TestGraph.insertSearchableReportedScene(
                jdbc,
                MEMBER_ID + 1,
                CLIP_ID + 1,
                RUN_ID + 1,
                SCENE_ID + 1,
                EXEC_ID + 1,
                RESULT_ID + 1,
                FEEDBACK_ID + 1);
        jdbc.update("UPDATE npick.feedback SET resolution = 'patch_parse' WHERE feedback_id = ?", FEEDBACK_ID + 1);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID + 1, CLIP_ID + 1, FEEDBACK_ID + 1, 8305090L);
        TestGraph.insertPendingPatchRule(jdbc, FEEDBACK_ID + 1, 8305091L);
        // Fix round 1(authz): verify()가 이제 담당 검수자·대기 후보 존재를 검사하므로 함께 심는다.
        TestGraph.claimFeedback(jdbc, FEEDBACK_ID + 1, MEMBER_ID + 1);

        // 한 번의 verify 로 두 후보가 모두 flip 되어 검색된다(예외 없이 실행 1행)
        long execId = useCase.verify(FEEDBACK_ID + 1, MEMBER_ID + 1).executionId();
        assertThat(execId).isPositive();

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM npick.search_execution WHERE replay_of_feedback_id = ?",
                Long.class,
                FEEDBACK_ID + 1);
        assertThat(count).isEqualTo(1L); // 두 검증이 아니라 한 조합 검증
    }

    @Test
    @DisplayName("한 신고의 규칙 후보 셋을 모두 임시 활성화하고 교체 대상은 제외한다")
    void verifiesEveryRuleCandidate() {
        long feedbackId = FEEDBACK_ID + 2;
        long oldRule = MULTI_OLD_RULE, firstCandidate = 8305181L;
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID + 2, CLIP_ID + 2, RUN_ID + 2, SCENE_ID + 2, EXEC_ID + 2, RESULT_ID + 2, feedbackId);
        jdbc.update("UPDATE npick.feedback SET resolution = 'patch_parse' WHERE feedback_id = ?", feedbackId);
        TestGraph.insertActivePatchRule(jdbc, feedbackId, oldRule);
        TestGraph.insertPendingPatchRule(jdbc, feedbackId, firstCandidate);
        TestGraph.insertPendingPatchRuleReplacing(jdbc, feedbackId, firstCandidate + 1, oldRule);
        TestGraph.insertPendingPatchRule(jdbc, feedbackId, firstCandidate + 2);
        TestGraph.claimFeedback(jdbc, feedbackId, MEMBER_ID + 2);

        long execId = useCase.verify(feedbackId, MEMBER_ID + 2).executionId();
        String context = jdbc.queryForObject(
                "SELECT verification_context_json::text FROM npick.search_execution WHERE search_execution_id = ?",
                String.class,
                execId);
        String ruleSet = jdbc.queryForObject(
                "SELECT (verification_context_json->'verification_rule_set')::text FROM npick.search_execution "
                        + "WHERE search_execution_id = ?",
                String.class,
                execId);

        assertThat(ruleSet)
                .contains(
                        String.valueOf(firstCandidate),
                        String.valueOf(firstCandidate + 1),
                        String.valueOf(firstCandidate + 2))
                .doesNotContain(String.valueOf(oldRule));
        assertThat(context).contains("\"candidate_rules\"");
        assertThat(jdbc.queryForObject(
                        "SELECT active FROM npick.search_rule WHERE search_rule_id = ?", Boolean.class, oldRule))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT active FROM npick.search_rule WHERE search_rule_id = ?", Boolean.class, firstCandidate))
                .isFalse();
    }

    @Test
    @DisplayName("복합 교정: 질의교정+장면제외+태그를 한 번의 검증·확정으로 셋 다 반영한다 (S15P21A501-309)")
    void confirmsCompositeCorrectionEndToEnd() {
        long member = MEMBER_ID + 3,
                clip = CLIP_ID + 3,
                run = RUN_ID + 3,
                scene = SCENE_ID + 3,
                exec = EXEC_ID + 3,
                result = RESULT_ID + 3,
                feedbackId = FEEDBACK_ID + 3;
        long parseRule = COMPOSITE_PARSE_RULE,
                excludeRule = COMPOSITE_EXCLUDE_RULE,
                evidenceId = 8305193L,
                execId = 8305194L;
        TestGraph.insertSearchableReportedScene(jdbc, member, clip, run, scene, exec, result, feedbackId);
        // 통합 판정. 세 종류 후보를 한 신고 아래 함께 담는다.
        jdbc.update("UPDATE npick.feedback SET resolution = 'correction' WHERE feedback_id = ?", feedbackId);
        TestGraph.insertPendingPatchRule(jdbc, feedbackId, parseRule);
        insertPendingExcludeRule(feedbackId, excludeRule, scene);
        TestGraph.insertReviewerTagCandidate(jdbc, scene, clip, feedbackId, evidenceId);
        TestGraph.claimFeedback(jdbc, feedbackId, member);

        // 검증 실행을 직접 심는다 — 테스트 코퍼스에선 verify() 가 degraded 로 기록돼 확정의 succeeded 조회에
        // 걸리지 않으므로(운영에선 succeeded), 여기서는 확정의 다중 규칙 적용을 실 DB 로 증명하는 데 집중한다.
        // candidate_rules 에 질의교정+장면제외를 함께 싣고, approved_evidence_ids 에 태그 근거를 싣는다.
        String fingerprint = currentCorrectionState.currentFingerprint(feedbackId);
        seedCompositeReplay(execId, feedbackId, parseRule, excludeRule, evidenceId, fingerprint);
        confirmUseCase.confirm(new ConfirmCorrectionCommand(feedbackId, member, true, execId));

        // 질의교정·장면제외 규칙이 모두 활성화되고, 태그 근거가 확정되고, 신고가 correction 으로 종료된다 —
        // 예전처럼 규칙 2개에서 막히지 않는다.
        assertThat(active(parseRule)).isTrue();
        assertThat(active(excludeRule)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = ?", Boolean.class, evidenceId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM npick.feedback WHERE feedback_id = ?", String.class, feedbackId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                        "SELECT resolution FROM npick.feedback WHERE feedback_id = ?", String.class, feedbackId))
                .isEqualTo("correction");
    }

    private Boolean active(long ruleId) {
        return jdbc.queryForObject(
                "SELECT active FROM npick.search_rule WHERE search_rule_id = ?", Boolean.class, ruleId);
    }

    /** 질의교정+장면제외 규칙과 태그 근거를 함께 담은 성공한 검증(replay) 실행을 심는다. */
    private void seedCompositeReplay(
            long execId, long feedbackId, long parseRule, long excludeRule, long evidenceId, String fingerprint) {
        String context = "{\"resolution\":\"correction\",\"approved_evidence_ids\":[" + evidenceId + "],"
                + "\"approved_rule_id\":" + parseRule + ",\"replaced_rule_id\":null,"
                + "\"approved_rule_action\":\"patch_parse\","
                + "\"candidate_rules\":["
                + "{\"approved_rule_id\":" + parseRule + ",\"replaced_rule_id\":null,\"action\":\"patch_parse\"},"
                + "{\"approved_rule_id\":" + excludeRule + ",\"replaced_rule_id\":null,\"action\":\"exclude_scene\"}],"
                + "\"state_fingerprint\":\"" + fingerprint + "\"}";
        jdbc.update(
                "INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text,"
                        + " normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint,"
                        + " normalization_version, execution_type, replay_of_feedback_id, status, degraded_reasons_json,"
                        + " applied_excludes_json, search_config_json, config_version, verification_context_json,"
                        + " created_at, updated_at) VALUES"
                        + " (?, ?, 'q', 'q', '{}'::jsonb, '{}'::jsonb, ?, 'v1', 'replay', ?, 'succeeded',"
                        + " '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', CAST(? AS jsonb), now(), now())",
                execId,
                MEMBER_ID + 3,
                "fp-" + execId,
                feedbackId,
                context);
    }

    /** 대기 중인 장면 제외 후보(active=false, action=exclude_scene, target_scene_id). condition/patch 는 NULL 이다. */
    private void insertPendingExcludeRule(long feedbackId, long ruleId, long sceneId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO npick.search_rule(search_rule_id, query_fingerprint, normalized_query, "
                        + "normalized_filters_json, normalization_version, action, target_scene_id, "
                        + "source_feedback_id, active, created_at, updated_at) "
                        + "VALUES (?, ?, '원본질의', '{}'::jsonb, 'norm/v1', 'exclude_scene', ?, ?, false, ?, ?) "
                        + "ON CONFLICT DO NOTHING",
                ruleId,
                "fp-" + ruleId,
                sceneId,
                feedbackId,
                now,
                now);
    }
}
