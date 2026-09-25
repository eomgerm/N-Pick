package com.npick.search.application.query.structured;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.npick.search.domain.model.IneligibleReason;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.domain.model.EffectiveTag;

/** #54·#60의 계산 재현·explain_json 재료. finalResolution은 명시 필터·날짜·anchor 출처를 그대로 보존한다. */
public record StructuredScoresResult(
        QueryResolution finalResolution,
        StructuredScoreSettings settings,
        List<SceneScore> scenes,
        List<Ineligible> ineligibleScenes) {
    public StructuredScoresResult {
        scenes = List.copyOf(scenes);
        ineligibleScenes = List.copyOf(ineligibleScenes);
    }

    /** 사유를 함께 남겨 #60이 {@code filtered_json} 을 쓸 때 제외 근거를 다시 조회하지 않는다. 정렬 순서는 sceneId다. */
    public record Ineligible(long sceneId, IneligibleReason reason) {
        public Ineligible {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * 정렬 순서는 sceneId이며 검색 순위가 아니다. 두 후보 경로가 겹쳐도 장면 결과는 하나다.
     *
     * @param score 개체 축 가중평균({@code axes} 기여 합) + {@code keyword.bonus()}. 범위는 {@code [0, 1 + keywordWeight]}
     * @param denominator 개체 축 가중치 합. 키워드는 들어가지 않는다
     * @param keyword 키워드 태그 매칭 결과 (S15P21A501-321). 조건이 없으면 {@link KeywordScore#NONE}
     */
    public record SceneScore(
            long sceneId,
            long clipId,
            boolean inputCandidate,
            boolean tagCandidate,
            double score,
            double denominator,
            List<AxisScore> axes,
            KeywordScore keyword) {
        public SceneScore {
            axes = List.copyOf(axes);
            Objects.requireNonNull(keyword, "keyword");
        }

        /** 테스트·레거시 호출부 전용. 운영 코드는 8인자 생성자를 써야 한다 — 아니면 keyword 점수가 사라진다. */
        public SceneScore(
                long sceneId,
                long clipId,
                boolean inputCandidate,
                boolean tagCandidate,
                double score,
                double denominator,
                List<AxisScore> axes) {
            this(sceneId, clipId, inputCandidate, tagCandidate, score, denominator, axes, KeywordScore.NONE);
        }
    }

    /**
     * 키워드 태그 가산점과 근거 (S15P21A501-321).
     *
     * @param bonus 질의 명사 조건 충족률 × weight. 확장어 조건은 세지 않는다
     * @param queryConditions 질의 명사 조건별 일치
     * @param expandedConditions 확장어 조건별 일치. 편입 근거일 뿐 점수가 아니다
     */
    public record KeywordScore(
            double weight,
            double bonus,
            List<ConditionMatch> queryConditions,
            List<ConditionMatch> expandedConditions) {
        public static final KeywordScore NONE = new KeywordScore(0, 0, List.of(), List.of());

        public KeywordScore {
            queryConditions = List.copyOf(queryConditions);
            expandedConditions = List.copyOf(expandedConditions);
        }

        /** 근거 설명(match_evidence)에 싣는 태그. 질의 명사로 맞은 것이 먼저다. */
        public List<EffectiveTag> matchedTags() {
            return Stream.concat(queryConditions.stream(), expandedConditions.stream())
                    .flatMap(match -> match.matchedTags().stream())
                    .distinct()
                    .toList();
        }
    }

    /** missing은 해당 종류의 유효 태그가 전혀 없음을 뜻한다. 불일치와 미검증은 별도 근거로 남는다. */
    public record AxisScore(
            StructuredAxis axis,
            double weight,
            double score,
            double contribution,
            boolean missing,
            List<ConditionMatch> conditions,
            List<EffectiveTag> observedTags) {
        public AxisScore {
            conditions = List.copyOf(conditions);
            observedTags = List.copyOf(observedTags);
        }
    }

    /** 동일 조건의 복수 태그·근거는 추가 점수가 아니다. 검증·상속 범위·출처는 EffectiveTag 그대로다. */
    public record ConditionMatch(TagCondition condition, List<EffectiveTag> matchedTags) {
        public ConditionMatch {
            matchedTags = List.copyOf(matchedTags);
        }

        public boolean matched() {
            return !matchedTags.isEmpty();
        }
    }
}
