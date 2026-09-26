package com.npick.search.application.query.search;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.application.query.fusion.FuseSearchRankingQuery;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.domain.model.KeywordTagSettings;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;

/** 키워드 가산점은 구조화 점수에 합쳐지므로 따로 남겨야 「왜 이 점수였나」 를 기록으로 되짚는다 (S15P21A501-321). */
class SearchRecordPayloadTest {

    @Test
    void recordsKeywordBonusSeparatelyFromStructuredScore() {
        var keywordTag = new EffectiveTag(
                9301,
                9101,
                7001,
                TagType.KEYWORD,
                "전세사기",
                "전세사기",
                EffectiveTag.Verification.UNVERIFIED,
                EffectiveTag.Scope.SCENE,
                "vlm");
        var keyword = new StructuredScoresResult.KeywordScore(
                0.5,
                0.5,
                List.of(new StructuredScoresResult.ConditionMatch(
                        TagCondition.exact(TagType.KEYWORD, "전세사기"), List.of(keywordTag))),
                List.of());
        var structured = new StructuredScoresResult(
                QueryResolution.withoutAiInterpretation(),
                settings(),
                List.of(new StructuredScoresResult.SceneScore(9301, 9101, false, true, 1.5, 1, List.of(), keyword)),
                List.of());
        var candidates = new SearchCandidates(
                List.of(),
                new FuseSearchRankingQuery(List.of(), null, structured),
                new FalseHitGuardResult(List.of(), List.of(), false),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());

        @SuppressWarnings("unchecked")
        var scored = (List<Map<String, Object>>)
                SearchRecordPayload.candidates(candidates).structured().get("scored");

        assertThat(scored.getFirst())
                .containsEntry("score", 1.5)
                .containsEntry("keyword_bonus", 0.5)
                .containsEntry("keyword_tag_ids", List.of("7001"));
    }

    private static StructuredScoreSettings settings() {
        var weights = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (StructuredAxis axis : StructuredAxis.values()) weights.put(axis, 1.0);
        return new StructuredScoreSettings(
                StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights, new KeywordTagSettings(0.5, 12, List.of()));
    }
}
