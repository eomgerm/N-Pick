package com.npick.search;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.npick.common.persistence.CorrectionStateFingerprint;
import com.npick.feedback.application.port.VerificationRun;
import com.npick.feedback.application.port.VerificationRunPort;
import com.npick.search.application.query.search.PendingCandidates;
import com.npick.search.application.query.search.PendingCandidatesPort;
import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 리뷰 지적(S15P21A501-83, MR!135): 후보를 로드한 뒤 지문의 {@code pending_rules}/{@code pending_tags} 축을 재조회로
 * 내면, 로드와 그 재조회 사이에 다른 후보가 커밋돼도 지문에 잡힌다 — 그런데 검색·flip 은 로드 시점 집합만 대상이라
 * recorded fingerprint 가 실제로 검색한 집합보다 넓어진다. 확정(-84)의 현재 재조회 지문도 그 넓은 값과 같아져 드리프트
 * 가드를 통과해, 사실은 검사하지 않은 후보까지 확정된다.
 *
 * <p>수정: {@code VerificationSearchService.verify} 는 {@link PendingCandidatesPort#load} 로 읽은 값 그대로
 * {@code CorrectionStateFingerprint.compute(long, List, List)} 에 넘긴다 — recorded 값이 항상 flip 한 집합과 같다.
 *
 * <p>실제 스레드 경합 없이, {@link PendingCandidatesPort#load} 스텁의 부작용으로 "load 직후 다른 후보가 커밋된다"를
 * 결정적으로 재현한다: 스텁이 A 만 담긴 스냅샷을 돌려주면서, 그 응답을 만드는 동안 B 를 실제로 커밋해 둔다.
 *
 * <p>각 테스트는 {@link VerificationCombinationDbTest} 처럼 서로 다른 오프셋으로 완전히 독립된 신고 그래프를 심는다 —
 * 지문의 pending 축은 {@code source_feedback_id} 로 신고별로 갈리므로, 같은 feedbackId 를 공유하면 다른 테스트가 남긴
 * 대기 후보가 「경합 없음」 대칭 검증을 오염시킨다.
 */
class VerificationFingerprintRaceDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8308001L, CLIP_ID = 8308010L, RUN_ID = 8308020L,
            SCENE_ID = 8308030L, EXEC_ID = 8308040L, RESULT_ID = 8308050L, FEEDBACK_ID = 8308060L,
            EVIDENCE_A = 8308070L, EVIDENCE_B = 8308071L;

    @MockitoBean
    private PendingCandidatesPort candidatesPort;

    @Autowired
    private VerifyCorrectionCandidatesUseCase useCase;

    @Autowired
    private VerificationRunPort verificationRuns; // -84 소비자

    @Autowired
    private CorrectionStateFingerprint fingerprint; // -84 CurrentCorrectionStateQueryAdapter 와 같은 재조회 경로

    /** offset 만큼 떨어진 독립된 최소 그래프를 심고 태그 후보 A 를 단다. */
    private void seedFeedback(long offset, long sceneId, long clipId, long feedbackId, long memberId, long evidenceA) {
        TestGraph.insertSearchableReportedScene(jdbc, memberId, clipId, RUN_ID + offset, sceneId,
                EXEC_ID + offset, RESULT_ID + offset, feedbackId);
        jdbc.update("UPDATE npick.feedback SET resolution = 'tag_correction' WHERE feedback_id = ?", feedbackId);
        TestGraph.insertReviewerTagCandidate(jdbc, sceneId, clipId, feedbackId, evidenceA);
        TestGraph.claimFeedback(jdbc, feedbackId, memberId);
    }

    @Test
    @DisplayName("recorded fingerprint 의 pending_tags 축은 실제로 flip 한 A 만 담고, load 직후 커밋된 B 는 담지 않는다")
    void recordedFingerprintMatchesLoadedNotLaterCommitted() {
        long memberId = MEMBER_ID, clipId = CLIP_ID, sceneId = SCENE_ID, feedbackId = FEEDBACK_ID,
                evidenceA = EVIDENCE_A, evidenceB = EVIDENCE_B;
        seedFeedback(0, sceneId, clipId, feedbackId, memberId, evidenceA);
        // verify() 가 후보를 로드하는 그 순간 다른 요청이 B 를 커밋했다고 가정한다 — load() 는 그 순간의 스냅샷(A만)을
        // 돌려주지만, 스텁의 부작용으로 그 직후 B 가 DB 에 나타난다.
        given(candidatesPort.load(feedbackId)).willAnswer(invocation -> {
            TestGraph.insertReviewerTagCandidate(jdbc, sceneId, clipId, feedbackId, evidenceB);
            return new PendingCandidates("tag_correction", List.of(evidenceA), List.of());
        });

        long execId = useCase.verify(feedbackId, memberId).executionId();

        Optional<VerificationRun> run = verificationRuns.find(execId, feedbackId);
        assertThat(run).isPresent();
        String recorded = run.get().stateFingerprint();
        assertThat(recorded).contains("pending_tags=" + evidenceA);
        assertThat(recorded).doesNotContain(String.valueOf(evidenceB));

        // B 는 실제로 flip 되지 않았다 — 여전히 미확정 대기 상태다.
        assertThat(jdbc.queryForObject(
                "SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = ?", Boolean.class, evidenceB))
                .isFalse();
    }

    @Test
    @DisplayName("-84 재조회 경로(compute(feedbackId))는 B 까지 잡아 recorded 값과 달라진다 — 드리프트가 정확히 걸린다")
    void currentReQueryDiffersFromRecordedAfterRaceCommit() {
        long offset = 1;
        long memberId = MEMBER_ID + offset, clipId = CLIP_ID + offset, sceneId = SCENE_ID + offset,
                feedbackId = FEEDBACK_ID + offset, evidenceA = EVIDENCE_A + offset * 10, evidenceB = EVIDENCE_B + offset * 10;
        seedFeedback(offset, sceneId, clipId, feedbackId, memberId, evidenceA);
        given(candidatesPort.load(feedbackId)).willAnswer(invocation -> {
            TestGraph.insertReviewerTagCandidate(jdbc, sceneId, clipId, feedbackId, evidenceB);
            return new PendingCandidates("tag_correction", List.of(evidenceA), List.of());
        });

        long execId = useCase.verify(feedbackId, memberId).executionId();
        String recorded = verificationRuns.find(execId, feedbackId).orElseThrow().stateFingerprint();

        String currentReQueried = fingerprint.compute(feedbackId);

        assertThat(currentReQueried).isNotEqualTo(recorded); // 드리프트 감지 — 재검증 필요
        assertThat(currentReQueried).contains("pending_tags=").contains(String.valueOf(evidenceB));
    }

    @Test
    @DisplayName("경합이 없으면 recorded == 재조회 — 대칭은 여전히 성립한다")
    void symmetricWhenNoRaceCandidateAdded() {
        long offset = 2;
        long memberId = MEMBER_ID + offset, clipId = CLIP_ID + offset, sceneId = SCENE_ID + offset,
                feedbackId = FEEDBACK_ID + offset, evidenceA = EVIDENCE_A + offset * 10;
        seedFeedback(offset, sceneId, clipId, feedbackId, memberId, evidenceA);
        // 부작용 없는 스텁 — A 하나만 있는, 경합 없는 정상 경로.
        given(candidatesPort.load(feedbackId))
                .willReturn(new PendingCandidates("tag_correction", List.of(evidenceA), List.of()));

        long execId = useCase.verify(feedbackId, memberId).executionId();
        String recorded = verificationRuns.find(execId, feedbackId).orElseThrow().stateFingerprint();
        String currentReQueried = fingerprint.compute(feedbackId);

        assertThat(currentReQueried).isEqualTo(recorded);
    }
}
