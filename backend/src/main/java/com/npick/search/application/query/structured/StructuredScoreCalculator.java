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
        // 리졸버가 낸 항목을 여기서 재분류하지 않는다 — S15P21A501-48 이 확정한 계약이다. 값이 같은
        // entities/locations 교차 중복은 워커 validator 가 locations 를 남기고 걷어내고, 그것이 놓친
        // 표기 차이는 add() 의 TagMatchValue.normalize 가 축 안에서 접는다. 두 단계를 지나고도 남은
        // 것은 종류가 다른 조건이므로, 합치면 F-05 의 「기관과 시설은 다른 조건」 을 어긴다.
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
     * 개체 점수를 주면 정확 일치가 밀려나는데, 그것은 F-05 의 「확장어는 정확 일치를 대체할 수 없다」 를 어기는 것이다. 확장어가 쓰이는 곳은 단어
     * 검색의 후보 조회 하나이며 그 계약은 {@link com.npick.search.application.query.candidate.FindSceneCandidatesQueryPort} 에 있다.
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
