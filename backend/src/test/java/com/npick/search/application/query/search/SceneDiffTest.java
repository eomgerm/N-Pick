package com.npick.search.application.query.search;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.application.query.card.SceneCard;
import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult;
import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.soft.SoftRankingResult;
import com.npick.search.domain.model.GuardExclusionReason;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SceneDiff} 순수 계산 단위 테스트 (S15P21A501-83 Task 6, F-12 4).
 *
 * <p>실 파이프라인(BM25/dense/구조화)을 시드하지 않고도 진입/제외 판정과 이탈 사유 분기(제외 규칙/false-hit
 * guard/순위 이탈)를 직접 검증한다 — DbTest 는 배선만 확인한다(task-6-brief Step 1 참고).
 */
class SceneDiffTest {

    private static final long SCENE_A = 9301L;
    private static final long SCENE_B = 9302L;

    @Test
    @DisplayName("원 결과에 없던 장면이 검증 결과에 있으면 entered 에 실리고 이유가 채워진다")
    void entersScenesAbsentFromOriginal() {
        SearchCandidates verified = candidatesWith(List.of(scoredScene(SCENE_B)), List.of(), noGuardExclusions());

        SceneDiff.Result result = SceneDiff.of(List.of(SCENE_A), verified);

        assertThat(result.entered()).extracting(SceneDiff.Entered::sceneId).containsExactly(SCENE_B);
        assertThat(result.entered().getFirst().reason()).isNotEmpty();
        assertThat(result.dropped()).extracting(SceneDiff.Dropped::sceneId).containsExactly(SCENE_A);
    }

    @Test
    @DisplayName("원 결과에 있던 장면이 검증 결과에 없고 제외 규칙에도 안 걸리면 score_drop 이다")
    void dropsScenesAbsentFromVerifiedWithDefaultReason() {
        SearchCandidates verified = candidatesWith(List.of(), List.of(), noGuardExclusions());

        SceneDiff.Result result = SceneDiff.of(List.of(SCENE_A), verified);

        assertThat(result.dropped()).singleElement().satisfies(dropped -> {
            assertThat(dropped.sceneId()).isEqualTo(SCENE_A);
            assertThat(dropped.reason()).isEqualTo("score_drop");
        });
    }

    @Test
    @DisplayName("제외된 장면 사유는 approved_scene_exclusion 이다")
    void excludedSceneReasonIsApprovedExclusion() {
        SearchCandidates verified = candidatesWith(
                List.of(), List.of(new ActiveSceneExclusionResult.ExcludedScene(SCENE_A, List.of(7001L))),
                noGuardExclusions());

        SceneDiff.Result result = SceneDiff.of(List.of(SCENE_A), verified);

        assertThat(result.dropped()).singleElement().satisfies(dropped -> {
            assertThat(dropped.sceneId()).isEqualTo(SCENE_A);
            assertThat(dropped.reason()).isEqualTo("approved_scene_exclusion");
        });
    }

    @Test
    @DisplayName("false-hit guard 에 걸린 장면 사유는 false_hit_guard 다")
    void excludedByGuardReasonIsFalseHitGuard() {
        SearchCandidates verified = candidatesWith(List.of(), List.of(), guardExcluding(SCENE_A));

        SceneDiff.Result result = SceneDiff.of(List.of(SCENE_A), verified);

        assertThat(result.dropped()).singleElement().satisfies(dropped -> {
            assertThat(dropped.sceneId()).isEqualTo(SCENE_A);
            assertThat(dropped.reason()).isEqualTo("false_hit_guard");
        });
    }

    private static SearchCandidates candidatesWith(
            List<SearchCandidates.ScoredScene> scenes,
            List<ActiveSceneExclusionResult.ExcludedScene> appliedExcludes,
            FalseHitGuardResult guard) {
        return new SearchCandidates(scenes, null, guard, appliedExcludes, null, List.of(), List.of(), List.of());
    }

    private static FalseHitGuardResult noGuardExclusions() {
        return new FalseHitGuardResult(List.of(), List.of(), false);
    }

    private static FalseHitGuardResult guardExcluding(long sceneId) {
        return new FalseHitGuardResult(
                List.of(),
                List.of(new FalseHitGuardResult.SceneVerdict(
                        sceneId, GuardExclusionReason.EXPLICIT_DATE_CONFLICT, List.of())),
                true);
    }

    private static SearchCandidates.ScoredScene scoredScene(long sceneId) {
        SceneCard card = new SceneCard(sceneId, 9101L, "제목", "설명", 0, 1000, "b_roll", List.of(), null, List.of(), List.of());
        return new SearchCandidates.ScoredScene(
                sceneId,
                card.clipId(),
                card,
                List.of(),
                new FusionResult.ScoredCandidate(sceneId, card.clipId(), 1.0, 1.0, 0.0, 0.0, List.of()),
                new SoftRankingResult.OrderedCandidate(sceneId, card.clipId(), 1.0, 1.0, java.util.Map.of()),
                new FalseHitGuardResult.SceneVerdict(sceneId, null, List.of()));
    }
}
