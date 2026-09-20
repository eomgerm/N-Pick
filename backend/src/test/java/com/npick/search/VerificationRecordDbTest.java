package com.npick.search;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.npick.feedback.application.port.VerificationRun;
import com.npick.feedback.application.port.VerificationRunPort;
import com.npick.search.application.query.search.VerificationResult;
import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * replay 실행 기록과 verification_context_json (S15P21A501-83 Task 4). {@link AbstractVerificationSearchDbTest} 를
 * 확장해 리졸버 스텁을 공유한다 — 이 테스트는 「flip 이 rank() 에 반영되는가」가 아니라 「그 결과가 -84 가 읽을 수 있는 모양으로
 * 롤백 밖에 남는가」를 검사한다.
 */
class VerificationRecordDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8304001L, CLIP_ID = 8304010L, RUN_ID = 8304020L,
            SCENE_ID = 8304030L, EXEC_ID = 8304040L, RESULT_ID = 8304050L, FEEDBACK_ID = 8304060L,
            EVIDENCE_ID = 8304070L;

    @Autowired private VerifyCorrectionCandidatesUseCase useCase;
    @Autowired private VerificationRunPort verificationRuns; // -84 소비자

    @BeforeEach
    void seed() {
        TestGraph.insertSearchableReportedScene(jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        jdbc.update("UPDATE npick.feedback SET resolution = 'tag_correction' WHERE feedback_id = ?", FEEDBACK_ID);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID);
        // Fix round 1(authz): verify()가 이제 담당 검수자·대기 후보 존재를 검사하므로 함께 심는다.
        TestGraph.claimFeedback(jdbc, FEEDBACK_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("replay 행이 롤백 밖에 남고 -84 어댑터가 승인 스냅샷을 읽는다")
    void writesReadableReplayRow() {
        VerificationResult result = useCase.verify(FEEDBACK_ID, MEMBER_ID);
        assertThat(result.executionId()).isPositive();

        String type = jdbc.queryForObject(
                "SELECT execution_type FROM npick.search_execution WHERE search_execution_id = ?",
                String.class, result.executionId());
        assertThat(type).isEqualTo("replay");

        Optional<VerificationRun> run = verificationRuns.find(result.executionId(), FEEDBACK_ID);
        assertThat(run).isPresent();
        assertThat(run.get().resolution()).isEqualTo("tag_correction");
        assertThat(run.get().approvedEvidenceIds()).contains(EVIDENCE_ID);
        assertThat(run.get().stateFingerprint()).isNotBlank();
    }

    @Test
    @DisplayName("replay 행은 일반 검색과 같은 충실도로 기록된다 — 해석·설정·상태가 실 검색값(최소 기록 아님)")
    void recordsSearchWithFullFidelity() {
        long executionId = useCase.verify(FEEDBACK_ID, MEMBER_ID).executionId();
        var row = jdbc.queryForMap(
                "SELECT status, config_version, parsed_query_json FROM npick.search_execution "
                        + "WHERE search_execution_id = ?", executionId);
        // 후보에 degraded 사유가 없는 정상 검색이므로 succeeded. finalResolution·config 는 실값으로 남는다.
        assertThat(row.get("status")).isEqualTo("succeeded");
        assertThat(row.get("config_version")).asString().isNotBlank();
        assertThat(row.get("parsed_query_json")).isNotNull(); // finalResolution 기록됨 — null 최소기록이 아니다
    }
}
