package com.npick.search;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.QueryResolverErrorCode;
import com.npick.search.application.error.VerificationErrorCode;
import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * Task 7(S15P21A501-83): 검증 경로의 오류·격리·잔여 이음새 — 기록 커밋 시점 예외에도 {@code running} 이 누수되지 않고(§8), {@code complete()} 반환 결과 ID
 * 가 순위와 1:1 이며, 검색 성공이 자동 승인이 아님을 단언한다. Task 6 이 verify() 앞에 담당 검수자·REVIEWING·대기 후보 존재를 검사하는 인가 가드를 걸었으므로 모든 테스트가
 * 소유(reviewed_by_id)·REVIEWING·대기 후보를 함께 심는다.
 */
class VerificationErrorIsolationDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8307001L,
            CLIP_ID = 8307010L,
            RUN_ID = 8307020L,
            SCENE_ID = 8307030L,
            EXEC_ID = 8307040L,
            RESULT_ID = 8307050L,
            FEEDBACK_ID = 8307060L,
            EVIDENCE_ID = 8307070L;

    @Autowired
    VerifyCorrectionCandidatesUseCase useCase;

    @Test
    @DisplayName("검색 실패 시 replay 실행이 failed 로 닫힌다(running 누수 없음)")
    void closesFailedOnSearchFailure() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        claimAsReviewer(FEEDBACK_ID);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID);
        // record.start() 는 이미 커밋된 뒤다 — 그 다음 interpret() 안의 resolver 호출이 던지도록
        // 이 테스트에서만 스텁을 덮어써 「시작 뒤 실패」를 강제한다.
        willThrow(new BusinessException(QueryResolverErrorCode.RESOLVER_NETWORK))
                .given(resolver)
                .resolve(any());

        assertThatThrownBy(() -> useCase.verify(FEEDBACK_ID, MEMBER_ID)).isInstanceOf(BusinessException.class);

        var row = jdbc.queryForMap(
                "SELECT status, error_code FROM npick.search_execution WHERE replay_of_feedback_id = ? "
                        + "ORDER BY search_execution_id DESC LIMIT 1",
                FEEDBACK_ID);
        assertThat(row.get("status")).isEqualTo("failed"); // running 이 아니다
        assertThat(row.get("error_code")).isEqualTo(QueryResolverErrorCode.RESOLVER_NETWORK.code());
    }

    @Test
    @DisplayName("BusinessException 이 아닌 실패는 VerificationErrorCode.VERIFICATION_FAILED 로 닫힌다")
    void closesFailedWithGeneralErrorCodeOnNonBusinessFailure() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        claimAsReviewer(FEEDBACK_ID);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID);
        // BusinessException 이 아닌 일반 예외 — SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED(단어 검색 전용)를
        // 검증 실패 전반에 붙이던 옛 기본값 대신, VerificationErrorCode.VERIFICATION_FAILED 로 닫혀야 한다.
        willThrow(new IllegalStateException("boom")).given(resolver).resolve(any());

        assertThatThrownBy(() -> useCase.verify(FEEDBACK_ID, MEMBER_ID)).isInstanceOf(IllegalStateException.class);

        String errorCode = jdbc.queryForObject(
                "SELECT error_code FROM npick.search_execution WHERE replay_of_feedback_id = ? "
                        + "ORDER BY search_execution_id DESC LIMIT 1",
                String.class,
                FEEDBACK_ID);
        assertThat(errorCode).isEqualTo(VerificationErrorCode.VERIFICATION_FAILED.code());
    }

    @Test
    @DisplayName("complete() 반환 결과 ID 가 순위와 1:1 로 저장되고 장면이 중복 없다")
    void resultIdsMapOneToOneWithRank() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        // 검증 재검색이 찾아야 할 두 번째 장면 — 원 결과와 무관한 별도 클립/신고 아래 같은 캡션 토큰.
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID + 1, RUN_ID + 1, SCENE_ID + 1, EXEC_ID + 1, RESULT_ID + 1, FEEDBACK_ID + 1);
        claimAsReviewer(FEEDBACK_ID);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID);

        long execId = useCase.verify(FEEDBACK_ID, MEMBER_ID).executionId();

        List<Integer> ranks = jdbc.queryForList(
                "SELECT result_rank FROM npick.search_result WHERE search_execution_id = ? ORDER BY result_rank",
                Integer.class,
                execId);
        assertThat(ranks).isNotEmpty();
        // uq_search_result_rank 의 실제 값이 연속(1..n)임을 시드 건수에 하드코딩하지 않고 확인한다.
        assertThat(ranks)
                .isEqualTo(IntStream.rangeClosed(1, ranks.size()).boxed().toList());

        Long distinctScenes = jdbc.queryForObject(
                "SELECT count(DISTINCT scene_id) FROM npick.search_result WHERE search_execution_id = ?",
                Long.class,
                execId);
        assertThat(distinctScenes).isEqualTo((long) ranks.size()); // uq_search_result_scene — 장면 중복 없음
    }

    @Test
    @DisplayName("검증 성공은 자동 승인이 아니다 — feedback.status 는 verify 후에도 그대로다")
    void searchSuccessDoesNotAutoApprove() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        claimAsReviewer(FEEDBACK_ID);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID);

        useCase.verify(FEEDBACK_ID, MEMBER_ID);

        String status = jdbc.queryForObject(
                "SELECT status FROM npick.feedback WHERE feedback_id = ?", String.class, FEEDBACK_ID);
        assertThat(status).isEqualTo("REVIEWING"); // 검색 성공이 확정을 만들지 않는다 (F-12(5))
    }

    /** {@code TestGraph.insertReportedScene} 은 reviewed_by_id 를 채우지 않는다 — claim 은 이 신고의 신경 밖(-83). */
    private void claimAsReviewer(long feedbackId) {
        jdbc.update("UPDATE npick.feedback SET reviewed_by_id = ? WHERE feedback_id = ?", MEMBER_ID, feedbackId);
    }
}
