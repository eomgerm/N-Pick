package com.npick.search.application.query.soft;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.ShotType;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S15P21A501-55 의 완료 조건을 고정한다.
 *
 * <p>네 개는 「~하지 않는다」의 검증이다 — b-roll 가점이 추월하지 못한다, 최신순 강제 정렬이 없다, 정보가 없는 항목이 가점을 받지 않는다, soft 가 hard 제외로 변질되지 않는다.
 */
class SoftRankingServiceTest {

    private static final long CLIP_ID = 700;

    private final FindSceneShotTypesQueryPort shotTypes = mock(FindSceneShotTypesQueryPort.class);
    private final ResolveSceneTagsUseCase tags = mock(ResolveSceneTagsUseCase.class);

    /** 완료 조건: b-roll boost 가 검증된 매칭을 추월하지 못한다. */
    @Test
    void aBRollBoostCannotOvertakeACandidateWithAHigherBaseScore() {
        givenShotTypes(Map.of(1L, ShotType.ANCHOR, 2L, ShotType.B_ROLL));
        givenNoTags();

        var result = service(settings(1, 1, 1, 1))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.9), candidate(2, 0.5)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH)));

        // 2 번이 보조 점수를 독차지해도 baseScore 가 낮으면 뒤에 남는다.
        assertThat(sceneOrder(result)).containsExactly(1L, 2L);
        assertThat(softScoreOf(result, 2)).isEqualTo(1.0);
    }

    /** 완료 조건: 최신순 강제 정렬이 일어나지 않는다. */
    @Test
    void theRecentIntentStillDoesNotForceChronologicalOrder() {
        givenShotTypes(Map.of());
        givenTags(Map.of(
                1L, List.of(tag(1, TagType.BROADCAST_DATE, "2020-01-01")),
                2L, List.of(tag(2, TagType.BROADCAST_DATE, "2026-09-01"))));

        var result = service(settings(1, 0, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.9), candidate(2, 0.5)),
                        resolution(QueryResolution.Intent.RECENT_SCENE)));

        // 2 번이 훨씬 최신이고 최신성 만점이지만 관련성이 높은 1 번을 앞지르지 않는다.
        assertThat(sceneOrder(result)).containsExactly(1L, 2L);
        assertThat(softScoreOf(result, 2)).isEqualTo(1.0);
    }

    @Test
    void recencyDecidesTheOrderOnlyAmongCandidatesWithTheSameBaseScore() {
        givenShotTypes(Map.of());
        givenTags(Map.of(
                1L, List.of(tag(1, TagType.BROADCAST_DATE, "2020-01-01")),
                2L, List.of(tag(2, TagType.BROADCAST_DATE, "2026-09-01"))));

        var result = service(settings(1, 0, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5), candidate(2, 0.5)),
                        resolution(QueryResolution.Intent.RECENT_SCENE)));

        assertThat(sceneOrder(result)).containsExactly(2L, 1L);
    }

    /** 최신성은 검색 의도가 있을 때만이다. 상시 적용하면 최신 편향이 모든 질의에 깔린다. */
    @Test
    void recencyIsInactiveWhenTheQueryDoesNotAskForRecentScenes() {
        givenShotTypes(Map.of());
        givenNoTags();

        var result = service(settings(1, 0, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5), candidate(2, 0.5)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH)));

        // 활성 신호가 없으므로 태그를 읽을 이유도 없고, 동점은 sceneId 로 갈린다.
        verify(tags, never()).resolve(any());
        assertThat(sceneOrder(result)).containsExactly(1L, 2L);
        assertThat(softScoreOf(result, 1)).isZero();
    }

    /** 완료 조건: 정보가 없는 항목이 가점을 받지 않는다. */
    @Test
    void aSceneWithoutABroadcastDateEarnsNoRecencyPoints() {
        givenShotTypes(Map.of());
        givenTags(Map.of(
                2L, List.of(tag(2, TagType.BROADCAST_DATE, "2020-01-01")),
                3L, List.of(tag(3, TagType.BROADCAST_DATE, "2026-09-01"))));

        var result = service(settings(1, 0, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5), candidate(2, 0.5), candidate(3, 0.5)),
                        resolution(QueryResolution.Intent.RECENT_SCENE)));

        // 날짜가 없는 1 번은 0 이다. 가장 오래된 2 번과 같은 값이지 그보다 앞서는 값이 아니다.
        assertThat(softScoreOf(result, 1)).isZero();
        assertThat(softScoreOf(result, 2)).isZero();
        assertThat(softScoreOf(result, 3)).isEqualTo(1.0);
    }

    /** {@code unknown} 은 「B-roll 이 아님」이 아니라 「모름」이다. 모름에 가점을 주지 않는다 (F-05). */
    @Test
    void anUnknownShotTypeEarnsNoBRollPoints() {
        givenShotTypes(Map.of(1L, ShotType.UNKNOWN, 2L, ShotType.INTERVIEW));
        givenNoTags();

        var result = service(settings(0, 1, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5), candidate(2, 0.5)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH)));

        assertThat(softScoreOf(result, 1)).isZero();
        assertThat(softScoreOf(result, 2)).isZero();
    }

    @Test
    void seasonAndWeatherAreActiveOnlyWhenTheQueryCarriesThoseConditions() {
        givenShotTypes(Map.of());
        givenTags(Map.of(1L, List.of(tag(1, TagType.SEASON, "겨울"), tag(1, TagType.WEATHER, "눈"))));

        var withoutConditions = service(settings(0, 0, 1, 1))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5)), resolution(QueryResolution.Intent.SCENE_SEARCH)));
        assertThat(signalsOf(withoutConditions, 1)).isEmpty();

        var withSeason = service(settings(0, 0, 1, 1))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH, classification(SEASON_TYPE, "겨울"))));
        assertThat(signalsOf(withSeason, 1)).containsOnlyKeys(SoftSignal.SEASON);
        assertThat(softScoreOf(withSeason, 1)).isEqualTo(1.0);
    }

    @Test
    void aSeasonConditionThatDoesNotMatchTheSceneEarnsNothing() {
        givenShotTypes(Map.of());
        givenTags(Map.of(1L, List.of(tag(1, TagType.SEASON, "여름"))));

        var result = service(settings(0, 0, 1, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH, classification(SEASON_TYPE, "겨울"))));

        assertThat(softScoreOf(result, 1)).isZero();
    }

    /** 같은 축의 태그가 여러 개여도 신호는 0 또는 1 이다 (F-05 「같은 개체를 중복 계산하지 않는다」). */
    @Test
    void repeatedTagsOfTheSameTypeAreCountedOnce() {
        givenShotTypes(Map.of());
        givenTags(Map.of(
                1L, List.of(tag(1, TagType.SEASON, "겨울"), tag(1, TagType.SEASON, "겨울"), tag(1, TagType.SEASON, "겨울"))));

        var result = service(settings(0, 0, 1, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH, classification(SEASON_TYPE, "겨울"))));

        assertThat(softScoreOf(result, 1)).isEqualTo(1.0);
    }

    /**
     * 태그의 유일한 출처가 -161 판정기다.
     *
     * <p>교정이 반영되는지를 여기서 재현하지는 않는다 — 그것은 판정기 자신의 테스트다. 이 테스트가 막는 것은 보조 랭킹이 태그 테이블을 따로 읽어 교정을 우회하는 것이다 (F-05 완료 기준 「태그
     * 교정이 후보 추출·필터·점수·설명에 일관되게 반영된다」).
     */
    @Test
    void tagsAreReadOnlyThroughTheResolverSoCorrectionsCannotBeBypassed() {
        givenShotTypes(Map.of());
        // 판정기가 교정 후 값으로 "겨울" 을 돌려준다. 서비스는 이 결과만 본다.
        givenTags(Map.of(1L, List.of(tag(1, TagType.SEASON, "겨울"))));

        var result = service(settings(0, 0, 1, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH, classification(SEASON_TYPE, "겨울"))));

        verify(tags).resolve(List.of(1L));
        assertThat(softScoreOf(result, 1)).isEqualTo(1.0);
    }

    /** 완료 조건: 이 규칙들이 hard 제외로 변질되지 않는다. */
    @Test
    void softRankingNeverDropsACandidate() {
        givenShotTypes(Map.of(2L, ShotType.B_ROLL));
        givenNoTags();

        var result = service(settings(1, 1, 1, 1))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.9), candidate(2, 0.5), candidate(3, 0.0)),
                        resolution(QueryResolution.Intent.RECENT_SCENE)));

        assertThat(sceneOrder(result)).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void turningEverySignalOffSkipsTheLookupsAndLeavesTheBaseOrder() {
        var result = service(settings(0, 0, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5), candidate(2, 0.9)),
                        resolution(QueryResolution.Intent.RECENT_SCENE)));

        verify(shotTypes, never()).find(any());
        verify(tags, never()).resolve(any());
        assertThat(sceneOrder(result)).containsExactly(2L, 1L);
    }

    /**
     * {@code tieEpsilon} 을 켰을 때 보조 점수가 실제로 순서를 가른다.
     *
     * <p>이 테스트가 없으면 위의 완료 조건 테스트들이 자명해진다 — {@code epsilon=0} 에서 baseScore 가 다르면 비교자 첫 줄에서 갈려 보조 로직이 어떻게 망가져도 통과한다.
     */
    @Test
    void insideOneBucketTheSoftScoreDecidesTheOrder() {
        givenShotTypes(Map.of(2L, ShotType.B_ROLL));
        givenNoTags();

        // 0.54 와 0.52 는 floor(x/0.05) 가 둘 다 10 이라 같은 버킷이다.
        var result = service(settings(0.05, 0, 1, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.54), candidate(2, 0.52)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH)));

        assertThat(sceneOrder(result)).containsExactly(2L, 1L);
    }

    /** 완료 조건 ①: 버킷이 갈리면 보조 점수가 아무리 높아도 추월하지 못한다. */
    @Test
    void acrossBucketsTheBRollBoostStillCannotOvertake() {
        givenShotTypes(Map.of(2L, ShotType.B_ROLL));
        givenNoTags();

        // 0.56 은 버킷 11, 0.54 는 버킷 10 이다.
        var result = service(settings(0.05, 0, 1, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.56), candidate(2, 0.54)),
                        resolution(QueryResolution.Intent.SCENE_SEARCH)));

        assertThat(sceneOrder(result)).containsExactly(1L, 2L);
        assertThat(softScoreOf(result, 2)).isEqualTo(1.0);
    }

    /** 완료 조건 ②: {@code tieEpsilon} 을 켠 상태에서도 최신순 강제 정렬이 아니다. */
    @Test
    void acrossBucketsTheRecentIntentStillDoesNotForceChronologicalOrder() {
        givenShotTypes(Map.of());
        givenTags(Map.of(
                1L, List.of(tag(1, TagType.BROADCAST_DATE, "2020-01-01")),
                2L, List.of(tag(2, TagType.BROADCAST_DATE, "2026-09-01"))));

        var result = service(settings(0.05, 1, 0, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.56), candidate(2, 0.54)),
                        resolution(QueryResolution.Intent.RECENT_SCENE)));

        assertThat(sceneOrder(result)).containsExactly(1L, 2L);
    }

    /** 방송일 태그가 여러 개면 가장 늦은 것 하나만 쓴다 (F-05 「같은 개체를 중복 계산하지 않는다」). */
    @Test
    void onlyTheLatestBroadcastDateOfASceneIsUsed() {
        givenShotTypes(Map.of());
        givenTags(Map.of(
                1L, List.of(tag(1, TagType.BROADCAST_DATE, "2020-01-01"), tag(1, TagType.BROADCAST_DATE, "2026-09-01")),
                2L, List.of(tag(2, TagType.BROADCAST_DATE, "2023-05-05"))));

        var result = service(settings(1, 0, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5), candidate(2, 0.5)),
                        resolution(QueryResolution.Intent.RECENT_SCENE)));

        // 1 번의 늦은 쪽(2026-09-01)이 최댓값이다. 오래된 태그를 함께 세면 1 번이 2 번보다 뒤로 간다.
        assertThat(softScoreOf(result, 1)).isEqualTo(1.0);
        assertThat(softScoreOf(result, 2)).isZero();
    }

    /** 날짜가 있는 후보가 하나뿐이면 가를 것이 없다. 전원 만점을 주면 정보가 없는 장면만 상대적으로 벌을 받는다. */
    @Test
    void aSingleDatedCandidateLeavesEveryRecencyScoreAtZero() {
        givenShotTypes(Map.of());
        givenTags(Map.of(2L, List.of(tag(2, TagType.BROADCAST_DATE, "2026-09-01"))));

        var result = service(settings(1, 0, 0, 0))
                .adjust(new AdjustSoftRankingQuery(
                        List.of(candidate(1, 0.5), candidate(2, 0.5)),
                        resolution(QueryResolution.Intent.RECENT_SCENE)));

        assertThat(softScoreOf(result, 1)).isZero();
        assertThat(softScoreOf(result, 2)).isZero();
    }

    // --- 도우미 ---

    private static final QueryResolution.ClassificationType SEASON_TYPE = QueryResolution.ClassificationType.SEASON;

    private SoftRankingService service(SoftRankingSettings settings) {
        return new SoftRankingService(shotTypes, tags, settings);
    }

    private void givenShotTypes(Map<Long, ShotType> byScene) {
        when(shotTypes.find(any())).thenReturn(byScene);
    }

    private void givenTags(Map<Long, List<EffectiveTag>> byScene) {
        when(tags.resolve(any())).thenReturn(byScene);
    }

    private void givenNoTags() {
        givenTags(Map.of());
    }

    private static List<Long> sceneOrder(SoftRankingResult result) {
        return result.candidates().stream()
                .map(SoftRankingResult.OrderedCandidate::sceneId)
                .toList();
    }

    private static double softScoreOf(SoftRankingResult result, long sceneId) {
        return candidateOf(result, sceneId).softScore();
    }

    private static Map<SoftSignal, Double> signalsOf(SoftRankingResult result, long sceneId) {
        return candidateOf(result, sceneId).signals();
    }

    private static SoftRankingResult.OrderedCandidate candidateOf(SoftRankingResult result, long sceneId) {
        return result.candidates().stream()
                .filter(candidate -> candidate.sceneId() == sceneId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("후보가 결과에서 사라졌다: " + sceneId));
    }

    private static FusionResult.ScoredCandidate candidate(long sceneId, double baseScore) {
        return new FusionResult.ScoredCandidate(sceneId, CLIP_ID, baseScore, baseScore, 0, 0, List.of());
    }

    private static QueryResolution resolution(
            QueryResolution.Intent intent, QueryResolution.Classification... classifications) {
        return new QueryResolution(
                "query-resolver/v2",
                intent,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(classifications),
                List.of(),
                1.0);
    }

    private static QueryResolution.Classification classification(
            QueryResolution.ClassificationType type, String value) {
        return new QueryResolution.Classification(type, value, QueryResolution.Origin.INFERRED, null, 1.0);
    }

    private static EffectiveTag tag(long sceneId, TagType type, String matchValue) {
        return new EffectiveTag(
                sceneId,
                CLIP_ID,
                sceneId * 10 + type.ordinal(),
                type,
                matchValue,
                matchValue,
                EffectiveTag.Verification.UNVERIFIED,
                EffectiveTag.Scope.SCENE,
                "vlm");
    }

    private static SoftRankingSettings settings(double recency, double bRoll, double season, double weather) {
        return settings(0, recency, bRoll, season, weather);
    }

    private static SoftRankingSettings settings(
            double tieEpsilon, double recency, double bRoll, double season, double weather) {
        Map<SoftSignal, Double> weights = new EnumMap<>(SoftSignal.class);
        weights.put(SoftSignal.RECENCY, recency);
        weights.put(SoftSignal.B_ROLL, bRoll);
        weights.put(SoftSignal.SEASON, season);
        weights.put(SoftSignal.WEATHER, weather);
        return new SoftRankingSettings(weights, tieEpsilon, FusionSettings.WeightStatus.EXPERIMENTAL);
    }
}
