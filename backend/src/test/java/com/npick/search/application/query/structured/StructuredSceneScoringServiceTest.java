package com.npick.search.application.query.structured;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.IneligibleReason;
import com.npick.search.domain.model.KeywordTagSettings;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.Classification;
import com.npick.search.domain.model.QueryResolution.ClassificationType;
import com.npick.search.domain.model.QueryResolution.DateField;
import com.npick.search.domain.model.QueryResolution.DateWindow;
import com.npick.search.domain.model.QueryResolution.Entity;
import com.npick.search.domain.model.QueryResolution.EntityType;
import com.npick.search.domain.model.QueryResolution.IncidentName;
import com.npick.search.domain.model.QueryResolution.Location;
import com.npick.search.domain.model.QueryResolution.LocationType;
import com.npick.search.domain.model.QueryResolution.Origin;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.tag.application.query.FindTagMatchedScenesUseCase;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.application.query.TagMatchedScene;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 입력 fixture와 대역 포트 기반 검증. 실제 유효 태그 판정·SQL 연결은 별도 통합 테스트가 담당한다. */
class StructuredSceneScoringServiceTest {
    private final FindTagMatchedScenesUseCase candidates = mock(FindTagMatchedScenesUseCase.class);
    private final ResolveSceneTagsUseCase tags = mock(ResolveSceneTagsUseCase.class);
    private final FindEligibleScenesQueryPort eligible = mock(FindEligibleScenesQueryPort.class);

    @Test
    void countsOnlyActiveAxesAndKeepsMissingAndUnmatchedAxesInDenominator() {
        var r = resolution(
                List.of(new IncidentName("지진", Origin.INFERRED, null, .5)),
                List.of(person("홍길동"), person("김철수")),
                List.of(new Location(LocationType.LOCATION, "서울", Origin.INFERRED, null, .5)),
                List.of(),
                List.of(),
                List.of());
        var settings =
                settings(Map.of(StructuredAxis.EVENT, 2.0, StructuredAxis.PERSON, 3.0, StructuredAxis.LOCATION, 5.0));
        arrange(List.of(
                tag(1, TagType.PERSON, "홍길동", EffectiveTag.Verification.UNVERIFIED),
                tag(2, TagType.LOCATION, "부산", EffectiveTag.Verification.VERIFIED),
                tag(3, TagType.WEATHER, "맑음", EffectiveTag.Verification.VERIFIED)));
        var result = service(settings).score(new ScoreStructuredScenesQuery(r, List.of(30L)));
        var scene = result.scenes().getFirst();
        assertThat(scene.denominator()).isEqualTo(10);
        assertThat(scene.score()).isCloseTo(.15, within(1e-12));
        assertThat(scene.axes())
                .extracting(StructuredScoresResult.AxisScore::axis)
                .containsExactly(StructuredAxis.EVENT, StructuredAxis.PERSON, StructuredAxis.LOCATION);
        assertThat(axis(scene, StructuredAxis.EVENT).missing()).isTrue();
        assertThat(axis(scene, StructuredAxis.EVENT).score()).isZero();
        assertThat(axis(scene, StructuredAxis.PERSON).score()).isEqualTo(.5);
        assertThat(axis(scene, StructuredAxis.LOCATION).missing()).isFalse();
        assertThat(axis(scene, StructuredAxis.LOCATION).score()).isZero();
        assertThat(scene.score())
                .isEqualTo(scene.axes().stream()
                        .mapToDouble(StructuredScoresResult.AxisScore::contribution)
                        .sum());
        assertThat(result.settings()).isEqualTo(settings);
    }

    @Test
    void normalizesAndDeduplicatesSameTypedConditionsWithoutScoringExpandedTerms() {
        var r = resolution(
                List.of(),
                List.of(
                        new Entity(EntityType.ORGANIZATION, "Ａ 방송", Origin.EXPLICIT_FILTER, null, 1),
                        new Entity(EntityType.ORGANIZATION, "A\u200B방송", Origin.INFERRED, null, .5)),
                List.of(),
                List.of(),
                List.of(),
                List.of("A방송", "다른기관"));
        var observed = tag(1, TagType.ORGANIZATION, "A방송", EffectiveTag.Verification.REVIEWER_VERIFIED);
        arrange(List.of(observed, observed, tag(2, TagType.FACILITY, "A방송", EffectiveTag.Verification.VERIFIED)));
        var result = service(settings(Map.of())).score(new ScoreStructuredScenesQuery(r, List.of(30L, 30L)));
        var scene = result.scenes().getFirst();
        assertThat(result.scenes()).hasSize(1);
        assertThat(scene.score()).isEqualTo(1);
        assertThat(scene.denominator()).isEqualTo(1);
        assertThat(scene.axes()).singleElement().satisfies(axis -> {
            assertThat(axis.axis()).isEqualTo(StructuredAxis.ORGANIZATION);
            assertThat(axis.conditions()).hasSize(1);
            assertThat(axis.conditions().getFirst().matchedTags()).containsExactly(observed);
        });
        verify(candidates).find(List.of(TagCondition.exact(TagType.ORGANIZATION, "A방송")));
        assertThat(result.finalResolution()).isEqualTo(r);
    }

    @Test
    void sameNameWithDifferentTypesRemainsDistinctInNumeratorAndDenominator() {
        var r = resolution(
                List.of(),
                List.of(
                        new Entity(EntityType.ORGANIZATION, "A 방송", Origin.INFERRED, null, .5),
                        new Entity(EntityType.ORGANIZATION, "Ａ방송", Origin.INFERRED, null, .5)),
                List.of(
                        new Location(LocationType.FACILITY, "A방송", Origin.EXPLICIT_FILTER, null, 1),
                        new Location(LocationType.FACILITY, "A 방송", Origin.EXPLICIT_FILTER, null, 1)),
                List.of(),
                List.of(),
                List.of());
        arrange(List.of(tag(1, TagType.ORGANIZATION, "A방송", EffectiveTag.Verification.VERIFIED)));
        var result = service(settings(Map.of())).score(new ScoreStructuredScenesQuery(r, List.of(30L)));
        var scene = result.scenes().getFirst();
        assertThat(scene.denominator()).isEqualTo(2);
        assertThat(scene.score()).isEqualTo(.5);
        assertThat(axis(scene, StructuredAxis.ORGANIZATION).conditions()).hasSize(1);
        assertThat(axis(scene, StructuredAxis.ORGANIZATION).score()).isEqualTo(1);
        assertThat(axis(scene, StructuredAxis.FACILITY).conditions()).hasSize(1);
        assertThat(axis(scene, StructuredAxis.FACILITY).score()).isZero();
        assertThat(result.finalResolution()).isEqualTo(r);
        verify(candidates)
                .find(List.of(
                        TagCondition.exact(TagType.ORGANIZATION, "A방송"), TagCondition.exact(TagType.FACILITY, "A방송")));
    }

    @Test
    void preservesDateFieldsOriginsPrecisionAndExclusiveEndWithoutDateCandidateExpansion() {
        var broadcast = new DateWindow(
                DateField.BROADCAST_DATE,
                LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-04-01"),
                Origin.EXPLICIT_FILTER,
                null,
                1);
        var filmed = new DateWindow(
                DateField.FILMED_DATE,
                LocalDate.parse("2026-03-15"),
                LocalDate.parse("2026-03-16"),
                Origin.EXPLICIT_QUERY,
                new QueryResolution.QuerySpan(0, 10),
                .9);
        var r = resolution(
                List.of(), List.of(), List.of(), List.of(broadcast, broadcast, filmed), List.of(), List.of());
        arrange(List.of(tag(1, TagType.BROADCAST_DATE, "2026-03-31", EffectiveTag.Verification.UNVERIFIED)));
        var scene = service(settings(Map.of()))
                .score(new ScoreStructuredScenesQuery(r, List.of(30L)))
                .scenes()
                .getFirst();
        assertThat(scene.score()).isEqualTo(.5);
        assertThat(axis(scene, StructuredAxis.BROADCAST_DATE).conditions()).hasSize(1);
        assertThat(axis(scene, StructuredAxis.FILMED_DATE).missing()).isTrue();
        assertThat(axis(scene, StructuredAxis.BROADCAST_DATE)
                        .observedTags()
                        .getFirst()
                        .verification())
                .isEqualTo(EffectiveTag.Verification.UNVERIFIED);
        verifyNoInteractions(candidates);
        arrange(List.of(
                tag(1, TagType.BROADCAST_DATE, "2026-04-01", EffectiveTag.Verification.VERIFIED),
                tag(2, TagType.FILMED_DATE, "2026-03-15", EffectiveTag.Verification.REVIEWER_VERIFIED)));
        var result = service(settings(Map.of())).score(new ScoreStructuredScenesQuery(r, List.of(30L)));
        assertThat(axis(result.scenes().getFirst(), StructuredAxis.BROADCAST_DATE)
                        .score())
                .isZero();
        assertThat(axis(result.scenes().getFirst(), StructuredAxis.FILMED_DATE).score())
                .isEqualTo(1);
        assertThat(result.finalResolution()).isEqualTo(r);
    }

    @Test
    void noConditionsReturnsZeroAndIgnoresSeasonWeatherAndIntent() {
        var r = resolution(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new Classification(ClassificationType.SEASON, "여름", Origin.INFERRED, null, .5),
                        new Classification(ClassificationType.WEATHER, "비", Origin.INFERRED, null, .5)),
                List.of("키워드"));
        arrange(List.of(tag(1, TagType.SEASON, "여름", EffectiveTag.Verification.VERIFIED)));
        var scene = service(settings(Map.of()))
                .score(new ScoreStructuredScenesQuery(r, List.of(30L)))
                .scenes()
                .getFirst();
        assertThat(scene.score()).isZero();
        assertThat(scene.denominator()).isZero();
        assertThat(scene.axes()).isEmpty();
        verifyNoInteractions(candidates);
    }

    @Test
    void sceneTypeIsScoredAsTagAndVerificationDoesNotChangeWeight() {
        var r = resolution(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new Classification(ClassificationType.SCENE_TYPE, "기자회견", Origin.INFERRED, null, .6)),
                List.of());
        for (var verification : EffectiveTag.Verification.values()) {
            var evidence = tag(1, TagType.SCENE_TYPE, "기자회견", verification);
            arrange(List.of(evidence));
            var scene = service(settings(Map.of()))
                    .score(new ScoreStructuredScenesQuery(r, List.of(30L)))
                    .scenes()
                    .getFirst();
            assertThat(scene.score()).isEqualTo(1);
            assertThat(scene.axes().getFirst().conditions().getFirst().matchedTags())
                    .containsExactly(evidence);
        }
    }

    @Test
    void includesAllTagOnlyCandidatesWithoutCutoffAndChecksEligibilityIndependently() {
        var r = resolution(List.of(), List.of(person("홍길동")), List.of(), List.of(), List.of(), List.of());
        var ids = LongStream.rangeClosed(1, 501).boxed().toList();
        when(candidates.find(any()))
                .thenReturn(ids.stream()
                        .map(id -> new TagMatchedScene(id, 10, List.of()))
                        .toList());
        when(eligible.find(any()))
                .thenReturn(new FindEligibleScenesQueryPort.Eligibility(
                        ids.stream()
                                .filter(id -> id != 3)
                                .map(id -> new FindEligibleScenesQueryPort.EligibleScene(id, 10))
                                .toList(),
                        List.of(
                                new StructuredScoresResult.Ineligible(3L, IneligibleReason.INACTIVE_RUN),
                                new StructuredScoresResult.Ineligible(999L, IneligibleReason.SCENE_NOT_FOUND))));
        when(tags.resolve(any())).thenReturn(Map.of());
        var result = service(settings(Map.of())).score(new ScoreStructuredScenesQuery(r, List.of(2L, 3L, 999L)));
        assertThat(result.scenes()).hasSize(500);
        assertThat(result.scenes()).anySatisfy(scene -> {
            assertThat(scene.sceneId()).isEqualTo(501);
            assertThat(scene.tagCandidate()).isTrue();
            assertThat(scene.inputCandidate()).isFalse();
            assertThat(scene.score()).isZero();
        });
        assertThat(result.scenes()).anySatisfy(scene -> {
            assertThat(scene.sceneId()).isEqualTo(2);
            assertThat(scene.inputCandidate()).isTrue();
            assertThat(scene.tagCandidate()).isTrue();
        });
        // 적격 판정이 만든 사유를 서비스가 그대로 넘긴다. 차집합으로 되만들면 여기서 사유가 사라진다.
        assertThat(result.ineligibleScenes())
                .containsExactly(
                        new StructuredScoresResult.Ineligible(3L, IneligibleReason.INACTIVE_RUN),
                        new StructuredScoresResult.Ineligible(999L, IneligibleReason.SCENE_NOT_FOUND));
        var all = new java.util.TreeSet<>(ids);
        all.add(999L);
        verify(eligible).find(all);
    }

    @Test
    void zeroWeightsDoNotReviveCandidateChannelOrProduceNan() {
        var r = resolution(List.of(), List.of(person("홍길동")), List.of(), List.of(), List.of(), List.of());
        arrange(List.of(tag(1, TagType.PERSON, "홍길동", EffectiveTag.Verification.VERIFIED)));
        var scene = service(settings(Map.of(StructuredAxis.PERSON, 0.0)))
                .score(new ScoreStructuredScenesQuery(r, List.of(30L)))
                .scenes()
                .getFirst();
        assertThat(scene.score()).isZero();
        assertThat(scene.axes().getFirst().contribution()).isZero();
        verifyNoInteractions(candidates);
    }

    @Test
    void blankNormalizedValuesAreInactiveAndInputListsAreSnapshots() {
        var people = new ArrayList<>(List.of(person("\u200B \uFEFF")));
        var r = resolution(List.of(), people, List.of(), List.of(), List.of(), List.of());
        var query = new ScoreStructuredScenesQuery(r, List.of());
        people.add(person("홍길동"));
        assertThat(service(settings(Map.of())).score(query).scenes()).isEmpty();
        verifyNoInteractions(candidates, tags, eligible);
    }

    @Test
    void keywordOnlySceneIsAdmittedAndScoredAsSeparateBonus() {
        // 캡션·대사·OCR 에 없는 말도 keyword 태그로 후보가 된다. 점수는 개체 축 분모 밖의 가산점이다.
        var r = resolution(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(candidates.find(any())).thenReturn(List.of(new TagMatchedScene(30, 10, List.of())));
        var matched = tag(1, TagType.KEYWORD, "전세사기", EffectiveTag.Verification.UNVERIFIED);
        arrange(List.of(matched));

        var result = service(keywordSettings())
                .score(new ScoreStructuredScenesQuery(r, List.of(), List.of("전세/nng", "사기/nng"), List.of()));

        var scene = result.scenes().getFirst();
        assertThat(scene.tagCandidate()).isTrue();
        assertThat(scene.inputCandidate()).isFalse();
        assertThat(scene.axes()).isEmpty();
        assertThat(scene.denominator()).isZero();
        assertThat(scene.keyword().bonus()).isCloseTo(0.5 / 3, within(1e-12));
        assertThat(scene.score()).isEqualTo(scene.keyword().bonus());
        assertThat(scene.keyword().matchedTags()).containsExactly(matched);
        verify(candidates)
                .find(List.of(
                        TagCondition.exactIgnoreCase(TagType.KEYWORD, "전세"),
                        TagCondition.exactIgnoreCase(TagType.KEYWORD, "사기"),
                        TagCondition.exactIgnoreCase(TagType.KEYWORD, "전세사기")));
    }

    @Test
    void entityScoreIsNotDilutedByUnmatchedKeywordConditions() {
        // 설계 §3: 가중평균에 넣으면 서울역 장소 태그 장면이 1.0 → 0.67 로 깎인다. 가산점이면 그대로다.
        var r = resolution(
                List.of(),
                List.of(),
                List.of(new Location(LocationType.LOCATION, "서울역", Origin.INFERRED, null, .5)),
                List.of(),
                List.of(),
                List.of());
        arrange(List.of(tag(1, TagType.LOCATION, "서울역", EffectiveTag.Verification.VERIFIED)));
        var without = service(keywordSettings())
                .score(new ScoreStructuredScenesQuery(r, List.of(30L)))
                .scenes()
                .getFirst();

        var with = service(keywordSettings())
                .score(new ScoreStructuredScenesQuery(r, List.of(30L), List.of("서울역/nnp", "광장/nng"), List.of()))
                .scenes()
                .getFirst();

        assertThat(with.score()).isEqualTo(without.score()).isEqualTo(1.0);
        assertThat(with.denominator()).isEqualTo(without.denominator()).isEqualTo(1.0);
        assertThat(with.keyword().bonus()).isZero();
        assertThat(with.keyword().queryConditions())
                .hasSize(3)
                .noneMatch(StructuredScoresResult.ConditionMatch::matched);
    }

    @Test
    void expandedTermsAdmitButNeverScore() {
        // S15P21A501-48: 확장어는 구조화 점수에 쓰지 않는다. 후보 편입만 한다.
        var r = resolution(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("전세사기"));
        when(candidates.find(any())).thenReturn(List.of(new TagMatchedScene(30, 10, List.of())));
        arrange(List.of(tag(1, TagType.KEYWORD, "전세사기", EffectiveTag.Verification.UNVERIFIED)));

        var scene = service(keywordSettings())
                .score(new ScoreStructuredScenesQuery(r, List.of(), List.of(), List.of(List.of("전세사기/nng"))))
                .scenes()
                .getFirst();

        assertThat(scene.tagCandidate()).isTrue();
        assertThat(scene.score()).isZero();
        assertThat(scene.keyword().bonus()).isZero();
        assertThat(scene.keyword().queryConditions()).isEmpty();
        assertThat(scene.keyword().expandedConditions())
                .singleElement()
                .satisfies(match -> assertThat(match.matched()).isTrue());
    }

    @Test
    void sameConditionMatchedTwiceCountsOnce() {
        // Review Focus 4: 같은 태그가 클립 상속과 장면 판단으로 두 번 와도 조건은 하나다. 가산점은 weight 를 넘지 않는다.
        var r = resolution(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(candidates.find(any())).thenReturn(List.of(new TagMatchedScene(30, 10, List.of())));
        var inherited = tag(1, TagType.KEYWORD, "서울", EffectiveTag.Verification.UNVERIFIED);
        var sceneLevel = new EffectiveTag(
                30,
                10,
                1,
                TagType.KEYWORD,
                "서울",
                "서울",
                EffectiveTag.Verification.REVIEWER_VERIFIED,
                EffectiveTag.Scope.SCENE,
                "reviewer_feedback");
        arrange(List.of(inherited, sceneLevel));

        var scene = service(keywordSettings())
                .score(new ScoreStructuredScenesQuery(r, List.of(), List.of("서울/nnp"), List.of()))
                .scenes()
                .getFirst();

        assertThat(scene.keyword().bonus()).isEqualTo(0.5);
        assertThat(scene.score()).isEqualTo(0.5);
    }

    @Test
    void zeroKeywordWeightDisablesAdmissionAndBonus() {
        // Review Focus 5: 끈 채널이 후보를 끌어오면 「가중치 0 = off」 가 깨진다. 확장어 편입도 멈춘다.
        var r = resolution(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("전세사기"));
        arrange(List.of(tag(1, TagType.KEYWORD, "전세", EffectiveTag.Verification.UNVERIFIED)));
        var off = new StructuredScoreSettings(
                StructuredScoreSettings.WeightStatus.EXPERIMENTAL,
                settings(Map.of()).weights(),
                new KeywordTagSettings(0.0, 12, List.of()));

        var result = service(off)
                .score(new ScoreStructuredScenesQuery(
                        r, List.of(30L), List.of("전세/nng"), List.of(List.of("전세사기/nng"))));

        verifyNoInteractions(candidates);
        assertThat(result.scenes().getFirst().keyword()).isEqualTo(StructuredScoresResult.KeywordScore.NONE);
        assertThat(result.scenes().getFirst().score()).isZero();
    }

    private static StructuredScoreSettings keywordSettings() {
        return new StructuredScoreSettings(
                StructuredScoreSettings.WeightStatus.EXPERIMENTAL,
                settings(Map.of()).weights(),
                new KeywordTagSettings(0.5, 12, List.of("앞")));
    }

    private void arrange(List<EffectiveTag> observed) {
        when(eligible.find(any()))
                .thenReturn(new FindEligibleScenesQueryPort.Eligibility(
                        List.of(new FindEligibleScenesQueryPort.EligibleScene(30, 10)), List.of()));
        when(tags.resolve(any())).thenReturn(Map.of(30L, observed));
    }

    private StructuredSceneScoringService service(StructuredScoreSettings settings) {
        return new StructuredSceneScoringService(candidates, tags, eligible, settings);
    }

    private static StructuredScoresResult.AxisScore axis(StructuredScoresResult.SceneScore scene, StructuredAxis axis) {
        return scene.axes().stream()
                .filter(value -> value.axis() == axis)
                .findFirst()
                .orElseThrow();
    }

    private static StructuredScoreSettings settings(Map<StructuredAxis, Double> overrides) {
        var weights = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (var axis : StructuredAxis.values()) weights.put(axis, 1.0);
        weights.putAll(overrides);
        return new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights);
    }

    private static Entity person(String value) {
        return new Entity(EntityType.PERSON, value, Origin.INFERRED, null, .5);
    }

    private static EffectiveTag tag(long id, TagType type, String value, EffectiveTag.Verification verification) {
        return new EffectiveTag(30, 10, id, type, value, value, verification, EffectiveTag.Scope.CLIP, "test");
    }

    private static QueryResolution resolution(
            List<IncidentName> incidents,
            List<Entity> entities,
            List<Location> locations,
            List<DateWindow> dates,
            List<Classification> classifications,
            List<String> expanded) {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.RECENT_SCENE,
                dates,
                incidents,
                entities,
                locations,
                classifications,
                expanded,
                .9);
    }
}
