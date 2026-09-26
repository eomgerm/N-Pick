package com.npick.search.application.query.search;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.query.candidate.FindSceneCandidatesQueryPort;
import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.application.query.card.FindSceneCardsQueryPort;
import com.npick.search.application.query.card.SceneCard;
import com.npick.search.application.query.dense.DenseCandidatesResult;
import com.npick.search.application.query.dense.DenseSearchSettings;
import com.npick.search.application.query.dense.FindDenseCandidatesQueryPort;
import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult;
import com.npick.search.application.query.exclusion.ApplyActiveSceneExclusionsUseCase;
import com.npick.search.application.query.expansion.TokenizeExpandedTermsPort;
import com.npick.search.application.query.fusion.FuseSearchRankingUseCase;
import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.query.guard.ApplyFalseHitGuardUseCase;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.soft.AdjustSoftRankingUseCase;
import com.npick.search.application.query.soft.SoftRankingResult;
import com.npick.search.application.query.structured.ScoreStructuredScenesQuery;
import com.npick.search.application.query.structured.ScoreStructuredScenesUseCase;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 후보 구간이 지키는 것 — 꺼진 채널을 건드리지 않고, 실제 내보낸 수로 부족 사유를 낸다 (S15P21A501-59). */
class SearchCandidatePipelineTest {

    private final FindSceneCandidatesQueryPort lexical = mock(FindSceneCandidatesQueryPort.class);
    private final FindDenseCandidatesQueryPort dense = mock(FindDenseCandidatesQueryPort.class);
    private final ScoreStructuredScenesUseCase structured = mock(ScoreStructuredScenesUseCase.class);
    private final FuseSearchRankingUseCase fusion = mock(FuseSearchRankingUseCase.class);
    private final AdjustSoftRankingUseCase soft = mock(AdjustSoftRankingUseCase.class);
    private final ResolveSceneTagsUseCase tags = mock(ResolveSceneTagsUseCase.class);
    private final ApplyFalseHitGuardUseCase guard = mock(ApplyFalseHitGuardUseCase.class);
    private final ApplyActiveSceneExclusionsUseCase exclusions = mock(ApplyActiveSceneExclusionsUseCase.class);
    private final FindSceneCardsQueryPort cards = mock(FindSceneCardsQueryPort.class);
    private final TokenizeExpandedTermsPort expandedTerms = mock(TokenizeExpandedTermsPort.class);
    private LexicalSearchSettings lexicalSettings = lexicalSettings(List.of());

    @Test
    @DisplayName("단어 채널이 꺼져 있으면 조회하지 않는다")
    void doesNotQueryTheLexicalChannelWhenItIsOff() {
        // FusionSettings 는 "채널 하나 이상이 양수" 만 강제한다. LEXICAL=0 은 유효한 설정이고,
        // 그때 후보를 넘기면 순위 결합이 INACTIVE_CHANNEL_RESULT_PRESENT 로 검색을 죽인다.
        SearchCandidatePipeline pipeline = pipeline(denseOnly());
        givenRankingOf();

        pipeline.rank(query());

        verify(lexical, never()).findByWords(anyList(), anyList());
    }

    @Test
    @DisplayName("의미 검색 채널이 꺼져 있으면 조회하지 않는다")
    void doesNotQueryTheDenseChannelWhenItIsOff() {
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        pipeline.rank(query());

        verify(dense, never()).find(any(), any());
    }

    @Test
    @DisplayName("카드가 없어 빠진 장면까지 세어 부족 사유를 낸다")
    void countsScenesDroppedForMissingCards() {
        // 계약 §5.1: 결과가 10개 미만이면 shortage_reasons 가 1개 이상이다. 제외 뒤 개수로만
        // 세면 카드가 없어 빠진 장면이 누락돼 "9개인데 사유 없음" 이 나간다.
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        Long[] ten = {9301L, 9302L, 9303L, 9304L, 9305L, 9306L, 9307L, 9308L, 9309L, 9310L};
        when(lexical.findByWords(anyList(), anyList()))
                .thenReturn(List.of(new SceneCandidateResult(9301, 9101, 1, 1, 1, 0, 0, 0, 0, 0)));
        givenRankingOf(ten);
        // 열 중 하나만 카드가 없다. 제외 뒤 개수는 10 이지만 실제로 내보내는 것은 9 다.
        when(cards.find(any())).thenReturn(cardsFor(9302L, 9303L, 9304L, 9305L, 9306L, 9307L, 9308L, 9309L, 9310L));

        SearchCandidates result = pipeline.rank(query());

        assertThat(result.scenes()).hasSize(9);
        assertThat(result.shortageReasons()).isNotEmpty();
    }

    private static Map<Long, SceneCard> cardsFor(Long... sceneIds) {
        return java.util.Arrays.stream(sceneIds)
                .collect(java.util.stream.Collectors.toMap(
                        id -> id,
                        id -> new SceneCard(
                                id, 9101, "제목", "설명", 0, 1000, "b_roll", List.of(), null, List.of(), List.of())));
    }

    @Test
    @DisplayName("확장어를 구 묶음 그대로 넘긴다")
    void passesExpandedPhrasesAsGroups() {
        // S15P21A501-302: 펼쳐 넘기면 후보 조회가 확장어를 OR 로 받아 「중국 음식」이 중국 OR 음식 이
        // 된다. 일부만 겹치는 구는 통째로 남긴다 — 토큰을 하나 빼면 must 가 그만큼 헐거워진다.
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(expandedTerms.tokenize(List.of("집중호우"), "norm/v1"))
                .thenReturn(List.of(List.of("집중호우", "질의"), List.of("호우")));
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        pipeline.rank(queryWithExpandedTerms());

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(lexical).findByWords(anyList(), captor.capture());
        assertThat(captor.getValue()).containsExactly(List.of("집중호우", "질의"), List.of("호우"));
    }

    @Test
    @DisplayName("원 질의 토큰만으로 이루어진 구는 넘기지 않는다")
    void dropsPhrasesFullyCoveredByQueryTokens() {
        // 그 구는 원 질의 절이 이미 거는 것과 같아, 남기면 두 절에서 각각 가산돼 F-05 의
        // "같은 개체를 중복 계산하지 않는다" 를 깬다.
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(expandedTerms.tokenize(List.of("집중호우"), "norm/v1")).thenReturn(List.of(List.of("질의"), List.of("호우")));
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        pipeline.rank(queryWithExpandedTerms());

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(lexical).findByWords(anyList(), captor.capture());
        assertThat(captor.getValue()).containsExactly(List.of("호우"));
    }

    @Test
    @DisplayName("근거 설명용 확장어 토큰은 평탄화된 형태를 그대로 유지한다")
    void keepsEvidenceTokensFlattened() {
        // matched_keywords 계약은 문자열 배열이다 (web-api §5.1). 구 단위 AND 는 후보 조회의
        // 사정이고 응답 형태를 바꾸지 않는다.
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(expandedTerms.tokenize(List.of("집중호우"), "norm/v1"))
                .thenReturn(List.of(List.of("집중호우", "질의"), List.of("호우")));
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        SearchCandidates result = pipeline.rank(queryWithExpandedTerms());

        assertThat(result.expandedTokens()).containsExactly("집중호우", "호우");
    }

    @Test
    @DisplayName("토큰화가 실패해도 원 질의로 검색을 이어간다")
    void continuesWithoutExpandedTermsWhenTokenizationFails() {
        // S15P21A501-48 계약 9: 확장어 부재·토큰화 실패는 degraded 가 아니다.
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(expandedTerms.tokenize(any(), any())).thenReturn(List.of());
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        SearchCandidates result = pipeline.rank(queryWithExpandedTerms());

        assertThat(result.degradedReasons()).isEmpty();
        verify(lexical).findByWords(anyList(), anyList());
    }

    @Test
    @DisplayName("캡션 범용어는 BM25 질의 토큰에서 빼고 근거 설명 토큰도 같은 값을 쓴다")
    void dropsGenericCaptionTermsFromTheLexicalQuery() {
        // S15P21A501-320: 「장면」 은 활성 장면 33% 의 캡션에 있다. 남기면 term_set OR 로 그만큼이 후보가 된다.
        lexicalSettings = lexicalSettings(List.of("장면/nng", "보이/vv", "화면/nng", "모습/nng"));
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        SearchCandidates result = pipeline.rank(query(List.of("화재/nng", "장면/nng")));

        verify(lexical).findByWords(List.of("화재/nng"), List.of());
        // matched_keywords 가 이 값을 원 질의 토큰으로 쓴다 — 정규화 결과를 보면 「장면」 이 칩으로 뜬다.
        assertThat(result.searchTokens()).containsExactly("화재/nng");
    }

    @Test
    @DisplayName("원 질의 토큰이 전부 범용어면 빼지 않는다")
    void keepsTheQueryWhenEveryTokenIsGeneric() {
        lexicalSettings = lexicalSettings(List.of("장면/nng", "화면/nng"));
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        SearchCandidates result = pipeline.rank(query(List.of("화면/nng", "장면/nng")));

        verify(lexical).findByWords(List.of("화면/nng", "장면/nng"), List.of());
        assertThat(result.searchTokens()).containsExactly("화면/nng", "장면/nng");
    }

    @Test
    @DisplayName("확장어 구 안의 범용어는 빼지 않고, 겹침은 범용어를 빼기 전 원 질의로 판정한다")
    void keepsGenericTermsInsideExpandedPhrases() {
        // 구는 must 라 범용어가 있어도 좁힐 뿐이다. 빼면 「자료 화면」 이 자료 단독 매칭으로 풀린다 (S15P21A501-320).
        lexicalSettings = lexicalSettings(List.of("장면/nng", "화면/nng", "모습/nng"));
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(expandedTerms.tokenize(List.of("집중호우"), "norm/v1"))
                .thenReturn(List.of(
                        List.of("자료/nng", "화면/nng"), // 그대로 남는다
                        List.of("화재/nng", "장면/nng"), // 사용자가 친 말뿐이다 → 버린다 (남기면 화재 중복 가산 + 장면 부활)
                        List.of("모습/nng"), // 범용어뿐이다 → 버린다
                        List.of("소방/nng", "장면/nng"))); // 그대로 남는다
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        SearchCandidates result = pipeline.rank(
                queryWithExpandedTerms(new QueryNormalization("화재 장면", List.of("화재/nng", "장면/nng"), "norm/v1")));

        verify(lexical)
                .findByWords(List.of("화재/nng"), List.of(List.of("자료/nng", "화면/nng"), List.of("소방/nng", "장면/nng")));
        // 칩에는 범용어를 싣지 않는다 — 같은 구의 자료·소방이 이유를 설명한다.
        assertThat(result.expandedTokens()).containsExactly("자료/nng", "소방/nng");
    }

    private RankSearchCandidatesUseCase.Query queryWithExpandedTerms() {
        return queryWithExpandedTerms(new QueryNormalization("질의", List.of("질의"), "norm/v1"));
    }

    private RankSearchCandidatesUseCase.Query queryWithExpandedTerms(QueryNormalization normalization) {
        QueryResolution withTerms = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("집중호우"),
                0.9);
        return new RankSearchCandidatesUseCase.Query(
                normalization, withTerms, null, NormalizedSearch.of("질의", Map.of(), "norm/v1"));
    }

    @Test
    @DisplayName("태그가 하나도 없는 장면이 순위에 들어도 검색이 죽지 않는다")
    void survivesScenesWithNoTags() {
        // TagResolutionPolicy 는 "유효 태그가 하나도 없는 장면은 키 자체가 없다" 고 못박아 두었고,
        // ApplyFalseHitGuardQuery 는 순위에 있는 장면의 키가 없으면 던진다. 태그 배선이 성긴 지금은
        // 대부분 장면이 무태그라, 그런 장면이 상위에 드는 순간 그 질의가 항상 500 이 된다.
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(lexical.findByWords(anyList(), anyList()))
                .thenReturn(List.of(new SceneCandidateResult(9301, 9101, 1, 1, 1, 0, 0, 0, 0, 0)));
        givenRankingOf(9301L);
        // resolve 가 그 장면을 아예 담지 않는다 — 실제 정책이 하는 그대로다.
        when(tags.resolve(any())).thenReturn(Map.of());

        SearchCandidates result = pipeline.rank(query());

        assertThat(result.scenes())
                .extracting(SearchCandidates.ScoredScene::sceneId)
                .containsExactly(9301L);
        assertThat(result.scenes().getFirst().tags()).isEmpty();
    }

    @Test
    @DisplayName("구조화 점수에 범용어 제외 전 질의 토큰과 확장어 구를 넘긴다")
    void passesRawQueryTokensAndExpandedPhrasesToStructuredScoring() {
        // 키워드 태그 매칭은 320 의 BM25 범용어 목록과 독립이다 (설계 §5.3). 파이프라인이 뺀 토큰을 넘기면
        // 제외 목록이 두 곳에서 겹쳐 걸리고, 한쪽만 바꿔도 다른 쪽이 따라 바뀐다.
        lexicalSettings = lexicalSettings(List.of("장면/nng"));
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(expandedTerms.tokenize(List.of("집중호우"), "norm/v1")).thenReturn(List.of(List.of("집중/nng", "호우/nng")));
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf();

        pipeline.rank(queryWithExpandedTerms(new QueryNormalization("전세 장면", List.of("전세/nng", "장면/nng"), "norm/v1")));

        var captor = ArgumentCaptor.forClass(ScoreStructuredScenesQuery.class);
        verify(structured).score(captor.capture());
        assertThat(captor.getValue().searchTokens()).containsExactly("전세/nng", "장면/nng");
        assertThat(captor.getValue().expandedPhrases()).containsExactly(List.of("집중/nng", "호우/nng"));
    }

    @Test
    @DisplayName("구조화 점수가 맞춘 키워드 태그를 장면과 함께 나른다")
    void carriesKeywordTagEvidenceFromStructuredScores() {
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(lexical.findByWords(anyList(), anyList())).thenReturn(List.of());
        givenRankingOf(9301L);
        var keywordTag = new EffectiveTag(
                9301,
                9101,
                7001,
                TagType.KEYWORD,
                "전세사기",
                "전세 사기",
                EffectiveTag.Verification.UNVERIFIED,
                EffectiveTag.Scope.SCENE,
                "vlm");
        var keyword = new StructuredScoresResult.KeywordScore(
                0.5,
                0.5,
                List.of(new StructuredScoresResult.ConditionMatch(
                        TagCondition.exact(TagType.KEYWORD, "전세사기"), List.of(keywordTag))),
                List.of());
        when(structured.score(any()))
                .thenReturn(new StructuredScoresResult(
                        resolution(),
                        structuredSettings(),
                        List.of(new StructuredScoresResult.SceneScore(
                                9301, 9101, false, true, 0.5, 0, List.of(), keyword)),
                        List.of()));

        SearchCandidates result = pipeline.rank(query());

        assertThat(result.scenes().getFirst().keywordEvidence()).containsExactly(keywordTag);
    }

    private SearchCandidatePipeline pipeline(FusionSettings settings) {
        return new SearchCandidatePipeline(
                lexical,
                dense,
                provider(),
                settings,
                lexicalSettings,
                structured,
                fusion,
                soft,
                tags,
                guard,
                exclusions,
                cards,
                expandedTerms);
    }

    private void givenRankingOf(Long... sceneIds) {
        List<Long> ids = List.of(sceneIds);
        when(structured.score(any()))
                .thenReturn(new StructuredScoresResult(resolution(), structuredSettings(), List.of(), List.of()));
        when(fusion.fuse(any())).thenReturn(new FusionResult(List.of(), config(), "cfg-v1"));
        when(soft.adjust(any()))
                .thenReturn(new SoftRankingResult(
                        ids.stream()
                                .map(id -> new SoftRankingResult.OrderedCandidate(id, 9101, 1.0, 1.0, Map.of()))
                                .toList(),
                        softSettings()));
        when(dense.find(any(), any()))
                .thenReturn(new DenseCandidatesResult(
                        DenseCandidatesResult.Status.AVAILABLE,
                        DenseCandidatesResult.Reason.NONE,
                        List.of(),
                        new DenseSearchSettings("bge-m3@" + "a".repeat(40), 200, 2.0).snapshot(),
                        null,
                        null));
        when(tags.resolve(any()))
                .thenReturn(ids.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> List.of())));
        when(guard.apply(any())).thenReturn(new FalseHitGuardResult(ids, List.of(), false));
        when(exclusions.apply(any())).thenReturn(new ActiveSceneExclusionResult(ids, List.of()));
        when(cards.find(any()))
                .thenReturn(ids.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                id -> id,
                                id -> new SceneCard(
                                        id, 9101, "제목", "설명", 0, 1000, "b_roll", List.of(), null, List.of(),
                                        List.of()))));
    }

    private RankSearchCandidatesUseCase.Query query() {
        return query(List.of("질의"));
    }

    private RankSearchCandidatesUseCase.Query query(List<String> searchTokens) {
        return new RankSearchCandidatesUseCase.Query(
                new QueryNormalization(String.join(" ", searchTokens), searchTokens, "norm/v1"),
                resolution(),
                null,
                NormalizedSearch.of("질의", Map.of(), "norm/v1"));
    }

    private ObjectProvider<DenseSearchSettings> provider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DenseSearchSettings> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(new DenseSearchSettings("bge-m3@" + "a".repeat(40), 200, 2.0));
        return provider;
    }

    private static QueryResolution resolution() {
        return QueryResolution.withoutAiInterpretation();
    }

    private static FusionSettings lexicalOnly() {
        return settings(1.0, 0.0);
    }

    private static FusionSettings denseOnly() {
        return settings(0.0, 1.0);
    }

    private static FusionSettings settings(double lexicalWeight, double denseWeight) {
        var weights = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        weights.put(FusionChannel.LEXICAL, lexicalWeight);
        weights.put(FusionChannel.DENSE, denseWeight);
        return new FusionSettings(60, 0.0, weights, FusionSettings.WeightStatus.EXPERIMENTAL);
    }

    private static SearchConfigSnapshot config() {
        return new SearchConfigSnapshot(
                lexicalOnly(), lexicalSettings(List.of()), null, structuredSettings(), softSettings());
    }

    private static LexicalSearchSettings lexicalSettings(List<String> excludedQueryTokens) {
        return new LexicalSearchSettings("candidate-v1", 1.0, 1.0, 1.0, 0.3, 0.0, 200, excludedQueryTokens);
    }

    private static StructuredScoreSettings structuredSettings() {
        var weights = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (StructuredAxis axis : StructuredAxis.values()) {
            weights.put(axis, 0.0);
        }
        return new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights);
    }

    private static SoftRankingSettings softSettings() {
        var weights = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        for (SoftSignal signal : SoftSignal.values()) {
            weights.put(signal, 0.0);
        }
        return new SoftRankingSettings(weights, 0, FusionSettings.WeightStatus.EXPERIMENTAL);
    }
}
