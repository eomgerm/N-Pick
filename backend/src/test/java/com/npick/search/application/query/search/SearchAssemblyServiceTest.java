package com.npick.search.application.query.search;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.QueryResolverPort;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.StartSearchExecution;
import com.npick.search.application.query.card.SceneCard;
import com.npick.search.application.query.fusion.FuseSearchRankingQuery;
import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.soft.SoftRankingResult;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.search.domain.repository.ParseRuleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 조립이 소유한 것은 순서와 경계다. 각 단계의 계산은 이미 자기 테스트가 있다 (S15P21A501-59). */
class SearchAssemblyServiceTest {

    private static final String RAW_QUERY = "설 연휴 서울역 귀성 인파";

    private static final LexicalSearchSettings LEXICAL = new LexicalSearchSettings("candidate-v1", 1.0, 1.0, 1.0, 200);

    private QueryResolverPort resolver;
    private ParseRuleRepository parseRules;
    private RankSearchCandidatesUseCase pipeline;
    private SearchExecutionRecordPort record;
    private SearchAssemblyService service;

    @BeforeEach
    void setUp() {
        resolver = mock(QueryResolverPort.class);
        parseRules = mock(ParseRuleRepository.class);
        pipeline = mock(RankSearchCandidatesUseCase.class);
        record = mock(SearchExecutionRecordPort.class);
        service = new SearchAssemblyService(resolver, parseRules, pipeline, record);

        when(parseRules.findActivePatchParseRules()).thenReturn(List.of());
        when(record.start(any())).thenReturn(700L);
    }

    @Test
    @DisplayName("살아남은 순서대로 rank 를 1부터 연속으로 매긴다")
    void assignsContiguousRanksFromOne() {
        // 계약 §5.1: rank 는 배열 위치와 같은 1부터 시작하는 연속 정수다. 중간에서 장면이 빠져도
        // 그 자리의 번호를 비워 두지 않는다 — 비우면 화면이 "2위가 없다" 를 그린다.
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101), scene(9302, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L, 802L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.results()).extracting(SearchExecutionResult.ResultCard::rank).containsExactly(1, 2);
        assertThat(result.results()).extracting(SearchExecutionResult.ResultCard::sceneId).containsExactly(9301L, 9302L);
    }

    @Test
    @DisplayName("기록이 준 결과 ID 를 순위 순서 그대로 카드에 붙인다")
    void attachesRecordedResultIdsInRankOrder() {
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101), scene(9302, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L, 802L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.executionId()).isEqualTo(700L);
        assertThat(result.results())
                .extracting(SearchExecutionResult.ResultCard::searchResultId)
                .containsExactly(801L, 802L);
    }

    @Test
    @DisplayName("일부 기능 누락이 없으면 succeeded 다")
    void succeedsWhenNothingDegraded() {
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.degradedReasons()).isEmpty();
        assertThat(result.resolved()).isTrue();
    }

    private void givenResolved() {
        when(resolver.resolve(RAW_QUERY)).thenReturn(new QueryResolutionResult(
                new QueryNormalization("설 연휴 서울역 귀성 인파", List.of("설", "연휴", "서울역", "귀성", "인파"), "norm/v1"),
                resolution(),
                List.of(),
                null,
                "query-resolver/v2",
                "prompt/v1",
                "model/v1",
                null));
    }

    private ExecuteSearchQuery query() {
        return new ExecuteSearchQuery(RAW_QUERY, ExplicitDateFilters.none(), 9001L);
    }

    private static QueryResolution resolution() {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9);
    }

    private SearchCandidates candidates(SearchCandidates.ScoredScene... scenes) {
        var structured = new StructuredScoresResult(resolution(), structuredSettings(), List.of(), List.of());
        return new SearchCandidates(
                List.of(scenes),
                new FuseSearchRankingQuery(List.of(), null, structured),
                new FalseHitGuardResult(List.of(), List.of(), false),
                List.of(),
                new SearchConfigSnapshot(fusionSettings(), LEXICAL, null, structuredSettings(), softSettings()),
                List.of(),
                List.of());
    }

    private static FusionSettings fusionSettings() {
        var weights = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        weights.put(FusionChannel.LEXICAL, 1.0);
        weights.put(FusionChannel.DENSE, 0.0);
        return new FusionSettings(60, 0.0, weights, FusionSettings.WeightStatus.EXPERIMENTAL);
    }

    private static SoftRankingSettings softSettings() {
        var weights = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        for (SoftSignal signal : SoftSignal.values()) {
            weights.put(signal, 0.0);
        }
        return new SoftRankingSettings(weights, 0, FusionSettings.WeightStatus.EXPERIMENTAL);
    }

    private static StructuredScoreSettings structuredSettings() {
        var weights = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (StructuredAxis axis : StructuredAxis.values()) {
            weights.put(axis, 0.0);
        }
        return new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights);
    }

    private SearchCandidates.ScoredScene scene(long sceneId, long clipId) {
        return new SearchCandidates.ScoredScene(
                sceneId,
                clipId,
                new SceneCard(
                        sceneId,
                        clipId,
                        "KBC 뉴스9",
                        "서울역 귀성 인파",
                        42000,
                        49000,
                        "b_roll",
                        List.of("서울역", "귀성", "인파"),
                        null,
                        List.of(),
                        List.of()),
                List.of(),
                new FusionResult.ScoredCandidate(sceneId, clipId, 1.0, 1.0, 0.0, 0.0, List.of()),
                new SoftRankingResult.OrderedCandidate(sceneId, clipId, 1.0, 1.0, Map.of()),
                new FalseHitGuardResult.SceneVerdict(sceneId, null, List.of()));
    }
}
