package com.npick.search.application.query.structured;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
        // 축 안의 표기 차이만 접는다. 교차 중복을 거르는 단계는 워커 validator 의 _fold 하나뿐이다.
        //
        // 그 _fold 가 놓친 것은 여기 그대로 남는데, 그래도 맞다. 타입이 다르면 다른 조건이기 때문이다 —
        // sameNameWithDifferentTypesRemainsDistinctInNumeratorAndDenominator 가 그 상태를 고정한다.
        // 두 겹 방어가 아니다. #59 가 그렇게 읽으면 잘못된 전제로 배선한다.
        //
        // _fold 와 normalize 는 규칙도 다르다 — 앞은 공백을 접고 casefold 를 걸며, 뒤는 공백을 지우고
        // casefold 를 걸지 않는다. entities "서울 역" 과 locations "서울역" 은 _fold 가 다르다고 보아
        // 거르지 않는다. 이 차이는 S15P21A501-101 소관으로 남겼다.
        //
        // 판정 기준은 백엔드의 normalize 하나다. 워커의 비교 키를 고치거나 이 규칙을 Python 에
        // 복제하지 않는다 — 구현이 둘이 되는 순간이 「조용히 0건」 의 원천이다 (S15P21A501-169).
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
     * <p><b>{@code expanded_terms} 도 여기 없다.</b> 확장어는 구조화 축 점수에 반영하지 않는다 (S15P21A501-48 계약). 확장어에
     * 개체 점수를 주면 검증된 태그가 맞은 것과 AI 가 낸 동의어가 같은 무게를 갖는데, F-05 는 「사용자가 직접 명시한 내용과 AI가 추정한 내용을
     * 구분한다」 를 요구한다. 확장어는 출처조차 없다 ({@code ResolutionAxis.carriesOrigin()}). 이 상태는 #52 의
     * {@code normalizesAndDeduplicatesSameTypedConditionsWithoutScoringExpandedTerms} 가 고정한다. <b>점수 경로에서 확장어를
     * 쓰는 곳은 없고</b> 단어 검색의 후보 조회에서만 쓰며, 그 계약은
     * {@link com.npick.search.application.query.candidate.FindSceneCandidatesQueryPort} 에 있다.
     *
     * <p><b>점수 밖에는 소비처가 있다.</b> {@link com.npick.search.domain.model.ResolutionAxis#EXPANDED_TERMS} 가 교정 규칙의 축으로
     * 이 값을 읽고 쓴다 (S15P21A501-49·-81, 검수 화면 「관련 검색어」). 확장어 목록을 후보 조회 전용으로 가공하거나 비우면 검수자가 저장한 해석
     * 교정이 조용히 무력화된다.
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
            var conditionMatches = conditions.stream()
                    .map(condition -> new StructuredScoresResult.ConditionMatch(
                            condition,
                            observed.stream()
                                    .filter(tag -> matches(condition, tag))
                                    .toList()))
                    .toList();
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
        return new StructuredScoresResult.SceneScore(
                scene.sceneId(), scene.clipId(), inputCandidate, tagCandidate, score, denominator, results);
    }

    private static boolean matches(TagCondition condition, EffectiveTag tag) {
        // matchValue는 #161·#169가 제공한 정규화 정본이다. 별칭 확장이나 검증 상태 승격을 하지 않는다.
        return tag.matchValue().compareTo(condition.fromInclusive()) >= 0
                && tag.matchValue().compareTo(condition.toInclusive()) <= 0;
    }
}
