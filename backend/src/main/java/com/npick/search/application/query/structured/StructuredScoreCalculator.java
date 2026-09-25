package com.npick.search.application.query.structured;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.npick.search.domain.model.KeywordTagSettings;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.search.domain.policy.StructuredScorePolicy;
import com.npick.search.domain.policy.StructuredScorePolicy.AxisCount;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagMatchValue;
import com.npick.tag.domain.model.TagType;

/** 최종 해석과 유효 태그를 점수 정책의 개수 입력으로 옮긴다. DB·리졸버 호출 없이 검증 가능하다. */
final class StructuredScoreCalculator {
    private final StructuredScorePolicy policy = new StructuredScorePolicy();

    Map<StructuredAxis, List<TagCondition>> conditions(QueryResolution resolution) {
        var axes = new EnumMap<StructuredAxis, LinkedHashSet<TagCondition>>(StructuredAxis.class);
        for (var incident : resolution.incidentNames()) {
            add(axes, StructuredAxis.EVENT, TagType.EVENT, incident.value());
        }
        // 고유 조건은 (타입, 정규화 값) 이다. 이름이 같아도 기관과 시설은 다른 조건이다 (F-05).
        //
        // 리졸버가 낸 항목을 여기서 재분류하지 않는다 — S15P21A501-48 이 확정한 계약이다.
        //
        // entities 는 PERSON·ORGANIZATION 으로, locations 는 LOCATION·FACILITY 로만 간다. 두 배열의 교차
        // 중복은 애초에 같은 축에 들어올 수 없으므로 add() 의 normalize 와 축별 Set 은 그것을 볼 일이 없다 —
        // 축 안의 표기 차이만 접는다.
        //
        // 교차 중복을 거르는 단계는 **어디에도 없다.** 이름이 같고 타입이 다르면 다른 조건이기 때문이다 —
        // sameNameWithDifferentTypesRemainsDistinctInNumeratorAndDenominator 가 그 상태를 고정한다.
        // 워커도 거르지 않는다 (S15P21A501-205 에서 validator 의 교차 중복 drop 을 지웠다. 그 drop 은
        // FRD F-05 가 「종류가 다르면 이름이 같아도 다른 조건이다 (기관과 시설)」 로 명시한 바로 그 쌍을
        // 지우고 있었다). #59 가 워커를 방어선으로 읽으면 잘못된 전제로 배선한다.
        //
        // 같은 대상이 두 축에 걸리면 축이 하나 늘어 **분모가 커진다.** 한쪽 태그만 맞은 장면은 그만큼
        // 점수가 깎이는데, 그게 F-05 의 「정보가 없는 항목에 가점을 주지 않는다」 이고 의도된 동작이다.
        // 해석기가 같은 대상을 양쪽에 올리는 빈도는 프롬프트 품질 문제로 Gate B 에서 본다.
        //
        // 판정 기준은 백엔드의 normalize 하나다. 이 규칙을 Python 에 복제하지 않는다 — 구현이 둘이 되는
        // 순간이 「조용히 0건」 의 원천이다 (S15P21A501-169).
        for (var entity : resolution.entities()) {
            switch (entity.type()) {
                case PERSON -> add(axes, StructuredAxis.PERSON, TagType.PERSON, entity.value());
                case ORGANIZATION -> add(axes, StructuredAxis.ORGANIZATION, TagType.ORGANIZATION, entity.value());
            }
        }
        for (var location : resolution.locations()) {
            switch (location.type()) {
                case LOCATION -> add(axes, StructuredAxis.LOCATION, TagType.LOCATION, location.value());
                case FACILITY -> add(axes, StructuredAxis.FACILITY, TagType.FACILITY, location.value());
            }
        }
        for (var classification : resolution.classifications()) {
            if (classification.type() == QueryResolution.ClassificationType.SCENE_TYPE) {
                add(axes, StructuredAxis.SCENE_TYPE, TagType.SCENE_TYPE, classification.value());
            }
        }
        for (var date : resolution.dateWindows()) {
            var axis =
                    switch (date.field()) {
                        case BROADCAST_DATE -> StructuredAxis.BROADCAST_DATE;
                        case FILMED_DATE -> StructuredAxis.FILMED_DATE;
                    };
            var type = axis == StructuredAxis.BROADCAST_DATE ? TagType.BROADCAST_DATE : TagType.FILMED_DATE;
            // #47가 정한 의미·정밀도는 그대로, 반열린 경계 변환은 기존 계약 한 곳에서만 한다.
            axes.computeIfAbsent(axis, ignored -> new LinkedHashSet<>())
                    .add(TagCondition.dates(type, date.start(), date.endExclusive()));
        }
        var result = new EnumMap<StructuredAxis, List<TagCondition>>(StructuredAxis.class);
        axes.forEach((axis, values) -> result.put(axis, List.copyOf(values)));
        return result;
    }

    private static void add(
            Map<StructuredAxis, LinkedHashSet<TagCondition>> axes, StructuredAxis axis, TagType type, String value) {
        String normalized = TagMatchValue.normalize(value);
        if (!normalized.isEmpty()) {
            axes.computeIfAbsent(axis, ignored -> new LinkedHashSet<>()).add(TagCondition.exact(type, normalized));
        }
    }

    /**
     * 날짜는 기존 후보 비교에만 쓴다. 계절·날씨·shot_type 은 이 후보 경로에 없다.
     *
     * <p><b>{@code expanded_terms} 도 여기 없다.</b> 확장어는 구조화 축 점수에 반영하지 않는다 (S15P21A501-48 계약). 확장어에 개체 점수를 주면 검증된 태그가 맞은
     * 것과 AI 가 낸 동의어가 같은 무게를 갖는데, F-05 는 「사용자가 직접 명시한 내용과 AI가 추정한 내용을 구분한다」 를 요구한다. 확장어는 출처조차 없다
     * ({@code ResolutionAxis.carriesOrigin()}). 이 상태는 #52 의
     * {@code normalizesAndDeduplicatesSameTypedConditionsWithoutScoringExpandedTerms} 가 고정한다. <b>점수 경로에서 확장어를 쓰는 곳은
     * 없다.</b> 확장어는 단어 검색의 후보 조회와, 키워드 태그 후보 편입({@link KeywordConditionExtractor}, 점수 0 — S15P21A501-321)에서만 쓰며, 단어 검색 쪽
     * 계약은 {@link com.npick.search.application.query.candidate.FindSceneCandidatesQueryPort} 에 있다.
     *
     * <p><b>점수 밖에는 소비처가 있다.</b> {@link com.npick.search.domain.model.ResolutionAxis#EXPANDED_TERMS} 가 교정 규칙의 축으로 이 값을
     * 읽고 쓴다 (S15P21A501-49·-81, 검수 화면 「관련 검색어」). 확장어 목록을 후보 조회 전용으로 가공하거나 비우면 검수자가 저장한 해석 교정이 조용히 무력화된다.
     */
    List<TagCondition> candidateConditions(
            Map<StructuredAxis, List<TagCondition>> axes, StructuredScoreSettings settings) {
        return axes.entrySet().stream()
                .filter(entry -> settings.weights().get(entry.getKey()) > 0)
                .flatMap(entry -> entry.getValue().stream())
                .filter(condition -> !condition.type().date())
                .toList();
    }

    StructuredScoresResult.SceneScore score(
            FindEligibleScenesQueryPort.EligibleScene scene,
            boolean inputCandidate,
            boolean tagCandidate,
            Map<StructuredAxis, List<TagCondition>> axes,
            KeywordConditionExtractor.Conditions keyword,
            List<EffectiveTag> tags,
            StructuredScoreSettings settings) {
        var counts = new EnumMap<StructuredAxis, AxisCount>(StructuredAxis.class);
        var matches = new EnumMap<StructuredAxis, List<StructuredScoresResult.ConditionMatch>>(StructuredAxis.class);
        var observations = new EnumMap<StructuredAxis, List<EffectiveTag>>(StructuredAxis.class);
        axes.forEach((axis, conditions) -> {
            var observed = tags.stream()
                    .filter(tag -> tag.tagType() == conditions.getFirst().type())
                    .distinct()
                    .sorted(Comparator.comparingLong(EffectiveTag::tagId))
                    .toList();
            var conditionMatches = conditionMatches(conditions, observed);
            counts.put(
                    axis,
                    new AxisCount(
                            conditions.size(),
                            (int) conditionMatches.stream()
                                    .filter(StructuredScoresResult.ConditionMatch::matched)
                                    .count(),
                            settings.weights().get(axis)));
            matches.put(axis, conditionMatches);
            observations.put(axis, observed);
        });
        double denominator = policy.denominator(List.copyOf(counts.values()));
        var results = new ArrayList<StructuredScoresResult.AxisScore>();
        counts.forEach((axis, count) -> results.add(new StructuredScoresResult.AxisScore(
                axis,
                count.weight(),
                count.score(),
                policy.contribution(count, denominator),
                observations.get(axis).isEmpty(),
                matches.get(axis),
                observations.get(axis))));
        double score = results.stream()
                .mapToDouble(StructuredScoresResult.AxisScore::contribution)
                .sum();
        // 가산점은 분모 밖이다 (S15P21A501-321). 축 기여 합에 더하기만 하므로 개체 축만 맞은 장면의 점수는 그대로다.
        var keywordScore = keywordScore(keyword, tags, settings.keyword());
        return new StructuredScoresResult.SceneScore(
                scene.sceneId(),
                scene.clipId(),
                inputCandidate,
                tagCandidate,
                score + keywordScore.bonus(),
                denominator,
                results,
                keywordScore);
    }

    private StructuredScoresResult.KeywordScore keywordScore(
            KeywordConditionExtractor.Conditions keyword, List<EffectiveTag> tags, KeywordTagSettings settings) {
        if (keyword.isEmpty()) {
            return StructuredScoresResult.KeywordScore.NONE;
        }
        var observed = tags.stream()
                .filter(tag -> tag.tagType() == TagType.KEYWORD)
                .distinct()
                .sorted(Comparator.comparingLong(EffectiveTag::tagId))
                .toList();
        var query = conditionMatches(keyword.query(), observed);
        var expanded = conditionMatches(keyword.expanded(), observed);
        int matched = (int) query.stream()
                .filter(StructuredScoresResult.ConditionMatch::matched)
                .count();
        return new StructuredScoresResult.KeywordScore(
                settings.weight(), policy.keywordBonus(query.size(), matched, settings.weight()), query, expanded);
    }

    private static List<StructuredScoresResult.ConditionMatch> conditionMatches(
            List<TagCondition> conditions, List<EffectiveTag> observed) {
        return conditions.stream()
                .map(condition -> new StructuredScoresResult.ConditionMatch(
                        condition,
                        observed.stream().filter(tag -> matches(condition, tag)).toList()))
                .toList();
    }

    private static boolean matches(TagCondition condition, EffectiveTag tag) {
        // matchValue는 #161·#169가 제공한 정규화 정본이다. 별칭 확장이나 검증 상태 승격을 하지 않는다.
        if (condition.ignoreCase()) {
            // 키워드 조건 (S15P21A501-321). 두 끝이 같으므로 한 값과 비교한다. SQL 의 lower() 비교와 짝이다.
            return tag.matchValue().equalsIgnoreCase(condition.fromInclusive());
        }
        return tag.matchValue().compareTo(condition.fromInclusive()) >= 0
                && tag.matchValue().compareTo(condition.toInclusive()) <= 0;
    }
}
