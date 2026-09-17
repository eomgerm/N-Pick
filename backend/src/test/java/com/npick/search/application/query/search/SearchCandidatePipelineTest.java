package com.npick.search.application.query.search;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import com.npick.search.application.query.fusion.FuseSearchRankingUseCase;
import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.query.guard.ApplyFalseHitGuardUseCase;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.soft.AdjustSoftRankingUseCase;
import com.npick.search.application.query.soft.SoftRankingResult;
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

    @Test
    @DisplayName("단어 채널이 꺼져 있으면 조회하지 않는다")
    void doesNotQueryTheLexicalChannelWhenItIsOff() {
        // FusionSettings 는 "채널 하나 이상이 양수" 만 강제한다. LEXICAL=0 은 유효한 설정이고,
        // 그때 후보를 넘기면 순위 결합이 INACTIVE_CHANNEL_RESULT_PRESENT 로 검색을 죽인다.
        SearchCandidatePipeline pipeline = pipeline(denseOnly());
        givenRankingOf();

        pipeline.rank(query());

        verify(lexical, never()).findByWords(anyList());
    }

    @Test
    @DisplayName("의미 검색 채널이 꺼져 있으면 조회하지 않는다")
    void doesNotQueryTheDenseChannelWhenItIsOff() {
        SearchCandidatePipeline pipeline = pipeline(lexicalOnly());
        when(lexical.findByWords(anyList())).thenReturn(List.of());
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
        when(lexical.findByWords(anyList())).thenReturn(List.of(new SceneCandidateResult(9301, 9101, 1, 1, 0)));
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

    private SearchCandidatePipeline pipeline(FusionSettings settings) {
        return new SearchCandidatePipeline(
                lexical, dense, provider(), settings, structured, fusion, soft, tags, guard, exclusions, cards);
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
                        new DenseSearchSettings("bge-m3@" + "a".repeat(40), 200).snapshot(),
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
        return new RankSearchCandidatesUseCase.Query(
                new QueryNormalization("질의", List.of("질의"), "norm/v1"),
                resolution(),
                null,
                NormalizedSearch.of("질의", Map.of(), "norm/v1"));
    }

    private ObjectProvider<DenseSearchSettings> provider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DenseSearchSettings> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(new DenseSearchSettings("bge-m3@" + "a".repeat(40), 200));
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
                lexicalOnly(),
                new LexicalSearchSettings("candidate-v1", 1.0, 1.0, 1.0, 200),
                null,
                structuredSettings(),
                softSettings());
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
