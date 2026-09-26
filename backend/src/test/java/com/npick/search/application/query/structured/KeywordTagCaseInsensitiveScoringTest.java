package com.npick.search.application.query.structured;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.KeywordTagSettings;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.tag.application.query.FindTagMatchedScenesUseCase;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.application.query.TagMatchedScene;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 워커가 소문자로 접은 질의 토큰이 대문자로 저장된 keyword 태그에 가산점을 받는다 (S15P21A501-321).
 *
 * <p>{@code StructuredSceneScoringServiceTest} 가 500줄 한도에 가까워 따로 둔다.
 */
class KeywordTagCaseInsensitiveScoringTest {
    private final FindTagMatchedScenesUseCase candidates = mock(FindTagMatchedScenesUseCase.class);
    private final ResolveSceneTagsUseCase tags = mock(ResolveSceneTagsUseCase.class);
    private final FindEligibleScenesQueryPort eligible = mock(FindEligibleScenesQueryPort.class);

    @Test
    void lowercasedQueryTokenMatchesUppercaseKeywordTag() {
        var r = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.RECENT_SCENE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                .9);
        when(candidates.find(any())).thenReturn(List.of(new TagMatchedScene(30, 10, List.of())));
        var kbs = new EffectiveTag(
                30,
                10,
                1,
                TagType.KEYWORD,
                "KBS",
                "KBS",
                EffectiveTag.Verification.UNVERIFIED,
                EffectiveTag.Scope.CLIP,
                "ocr");
        when(eligible.find(any()))
                .thenReturn(new FindEligibleScenesQueryPort.Eligibility(
                        List.of(new FindEligibleScenesQueryPort.EligibleScene(30, 10)), List.of()));
        when(tags.resolve(any())).thenReturn(Map.of(30L, List.of(kbs)));

        var scene = new StructuredSceneScoringService(candidates, tags, eligible, settings())
                .score(new ScoreStructuredScenesQuery(r, List.of(), List.of("kbs/sl"), List.of()))
                .scenes()
                .getFirst();

        verify(candidates).find(List.of(TagCondition.exactIgnoreCase(TagType.KEYWORD, "kbs")));
        assertThat(scene.keyword().bonus()).isEqualTo(0.5);
        assertThat(scene.keyword().matchedTags()).containsExactly(kbs);
    }

    private static StructuredScoreSettings settings() {
        var weights = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (var axis : StructuredAxis.values()) weights.put(axis, 1.0);
        return new StructuredScoreSettings(
                StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights, new KeywordTagSettings(0.5, 12, List.of()));
    }
}
