package com.npick.search.application.query.search;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.QueryResolverErrorCode;
import com.npick.search.application.error.SearchExecutionErrorCode;
import com.npick.search.application.error.SearchRuleErrorCode;
import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.QueryResolverPort;
import com.npick.search.application.port.RecordSearchExecutionResolution;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.SearchRecordingException;
import com.npick.search.application.port.StartSearchExecution;
import com.npick.search.application.query.card.SceneCard;
import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult;
import com.npick.search.application.query.fusion.FuseSearchRankingQuery;
import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.soft.SoftRankingResult;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.ResolutionAxis;
import com.npick.search.domain.model.ShortageReason;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.search.domain.repository.ParseRuleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 조립이 소유한 것은 순서와 경계다. 각 단계의 계산은 이미 자기 테스트가 있다 (S15P21A501-59). */
class SearchAssemblyServiceTest {

    private static final String RAW_QUERY = "설 연휴 서울역 귀성 인파";

    private static final LexicalSearchSettings LEXICAL =
            new LexicalSearchSettings("candidate-v1", 1.0, 1.0, 1.0, 0.3, 200, List.of());

    private QueryResolverPort resolver;
    private ParseRuleRepository parseRules;
    private RankSearchCandidatesUseCase pipeline;
    private SearchExecutionRecordPort record;
    private RecordingTimer searchTimer;
    private SearchAssemblyService service;

    @BeforeEach
    void setUp() {
        resolver = mock(QueryResolverPort.class);
        parseRules = mock(ParseRuleRepository.class);
        pipeline = mock(RankSearchCandidatesUseCase.class);
        record = mock(SearchExecutionRecordPort.class);
        searchTimer = new RecordingTimer();
        service = new SearchAssemblyService(resolver, parseRules, pipeline, record, searchTimer);

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

        assertThat(result.results())
                .extracting(SearchExecutionResult.ResultCard::rank)
                .containsExactly(1, 2);
        assertThat(result.results())
                .extracting(SearchExecutionResult.ResultCard::sceneId)
                .containsExactly(9301L, 9302L);
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

    @Test
    @DisplayName("규칙을 적용하면 parse_source 와 적용 기록이 같은 판정을 말한다")
    void appliedRuleIsReportedConsistently() {
        // 기록을 읽는 쪽이 parse_source 를 보든 applied_rules_json 을 보든 같은 답을 얻어야 한다.
        // S15P21A501-198 은 후자만 읽으므로 둘이 갈려도 그쪽에서는 검출되지 않는다.
        givenResolved();
        when(parseRules.findActivePatchParseRules()).thenReturn(List.of(intentRule()));
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.hasAppliedReviewRule()).isTrue();
        // start 시점의 출처는 resolver 다. 저장된 parse_source 가 resolver_rule 로 올라가는 것은
        // 어댑터가 appliedRules 에서 파생하기 때문이고(JdbcSearchExecutionRecordAdapter), 그 파생의
        // 입력을 조립이 채운다. 여기서 appliedRules 를 빠뜨리면 규칙이 걸린 검색이 조용히
        // resolver 로 기록된다 — 기록을 읽는 쪽에는 규칙이 안 걸린 것과 구분되지 않는다.
        assertThat(recordedResolution().parseSource()).isEqualTo(StartSearchExecution.ParseSource.RESOLVER);
        assertThat(completedRecord().appliedRules())
                .anyMatch(outcome -> outcome.status() == ParseRuleOutcome.Status.APPLIED);
    }

    @Test
    @DisplayName("규칙이 없으면 parse_source 는 resolver 이고 적용 기록도 비어 있다")
    void noAppliedRuleIsReportedConsistently() {
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.hasAppliedReviewRule()).isFalse();
        assertThat(recordedResolution().parseSource()).isEqualTo(StartSearchExecution.ParseSource.RESOLVER);
        assertThat(completedRecord().appliedRules())
                .noneMatch(outcome -> outcome.status() == ParseRuleOutcome.Status.APPLIED);
    }

    @Test
    @DisplayName("해석·규칙·대체 검색을 각각 다른 경로로 잰다")
    void measuresEachPathSeparately() {
        // §8.2 는 네 경로를 구분 측정하라고 요구한다. 합쳐 재면 95% 10초 목표가 어느 경로 때문에
        // 깨지는지 알 수 없다.
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));
        service.execute(query());
        assertThat(searchTimer.lastPath).isEqualTo(SearchPath.NORMAL);

        when(parseRules.findActivePatchParseRules()).thenReturn(List.of(intentRule()));
        service.execute(query());
        assertThat(searchTimer.lastPath).isEqualTo(SearchPath.PATCHED);

        givenResolverUnavailable();
        service.execute(query());
        assertThat(searchTimer.lastPath).isEqualTo(SearchPath.FALLBACK);
    }

    @Test
    @DisplayName("리졸버 장애면 원 검색어 토큰으로 이어가고 fallback 으로 안내한다")
    void fallsBackToWordSearchWhenResolverIsDown() {
        // §6.2: AI 해석 실패·시간 초과 → 원 검색어의 단어 검색으로 전환하고 일부 기능 누락 안내.
        // 검색 자체는 실패가 아니다.
        givenResolverUnavailable();
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.resolved()).isFalse();
        assertThat(result.degradedReasons()).containsExactly("resolver_fallback");
        assertThat(result.status()).isEqualTo("degraded");
        assertThat(pipelineQuery().normalization().searchTokens()).containsExactly("설", "연휴", "서울역", "귀성", "인파");
    }

    @Test
    @DisplayName("해석이 없어도 사용자가 직접 건 명시 필터는 살아 있다")
    void keepsExplicitFiltersWhenResolutionIsMissing() {
        // 명시 필터는 AI 와 무관한 사용자 조건이고 F-06 이 그 값을 hard 제외 근거로 쓴다.
        // 해석이 없다고 함께 버리면 사용자가 건 조건을 조용히 무시하게 된다.
        givenResolverUnavailable();
        when(pipeline.rank(any())).thenReturn(candidates());
        when(record.complete(any())).thenReturn(List.of());

        service.execute(new ExecuteSearchQuery(RAW_QUERY, broadcastFilter(), 9001L));

        assertThat(pipelineQuery().finalResolution().dateWindows())
                .singleElement()
                .satisfies(window -> {
                    assertThat(window.field()).isEqualTo(QueryResolution.DateField.BROADCAST_DATE);
                    assertThat(window.origin()).isEqualTo(QueryResolution.Origin.EXPLICIT_FILTER);
                    assertThat(window.start()).isEqualTo(LocalDate.of(2026, 2, 14));
                });
    }

    @Test
    @DisplayName("명시 필터가 다르면 지문도 다르다")
    void explicitFiltersChangeTheFingerprint() {
        // F-05: 지문값에는 정규화한 검색어·명시 필터·정규화 버전이 포함된다. 필터를 빼면 날짜만
        // 다른 두 검색이 같은 지문이 되어, 한쪽에 승인된 장면 제외가 다른 쪽에도 걸린다.
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates());
        when(record.complete(any())).thenReturn(List.of());

        service.execute(query());
        String withoutFilter = recordedResolution().normalizedSearch().fingerprint();

        service.execute(new ExecuteSearchQuery(RAW_QUERY, broadcastFilter(), 9001L));
        String withFilter = recordedResolution().normalizedSearch().fingerprint();

        assertThat(withFilter).isNotEqualTo(withoutFilter);
    }

    @Test
    @DisplayName("리졸버가 장애가 아닌 이유로 실패하면 검색도 실패한다")
    void failsWhenResolverFailureIsNotAnOutage() {
        // 해석할 수 없는 질의를 빈 결과의 성공 응답으로 위장하지 않는다 (F-06 완료 기준).
        when(resolver.resolve(RAW_QUERY))
                .thenReturn(new QueryResolutionResult(
                        normalization(),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        QueryResolverErrorCode.QUERY_NOT_NORMALIZABLE));

        assertThatThrownBy(() -> service.execute(query())).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("활성 규칙 조회가 실패하면 검색도 실패한다")
    void failsWhenActiveRuleLookupFails() {
        // §6.2: 활성 규칙 조회 실패는 사람의 결정을 조용히 건너뛰지 않고 검색 실패로 안내한다.
        givenResolved();
        when(parseRules.findActivePatchParseRules()).thenThrow(new IllegalStateException("조회 실패"));

        assertThatThrownBy(() -> service.execute(query()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(SearchExecutionErrorCode.ACTIVE_RULE_LOOKUP_FAILED);
    }

    @Test
    @DisplayName("실행을 열지 못하면 검색을 중단한다")
    void failsWhenTheExecutionCannotBeOpened() {
        // §6.2: 최초 저장 실패는 중단하고 재시도를 안내한다. 계속 진행하면 결과를 돌려주고도 그
        // 실행이 어디에도 없어, 신고·검증이 가리킬 대상이 사라진다.
        givenResolved();
        when(record.start(any())).thenThrow(new SearchRecordingException("열지 못했다"));

        assertThatThrownBy(() -> service.execute(query()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(SearchExecutionErrorCode.EXECUTION_NOT_RECORDED);
    }

    @Test
    @DisplayName("후보 조회가 터지면 0건의 성공이 아니라 검색 실패다")
    void failsWhenCandidateLookupBlowsUp() {
        givenResolved();
        when(pipeline.rank(any())).thenThrow(new IllegalStateException("색인 접근 실패"));

        assertThatThrownBy(() -> service.execute(query()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED);
    }

    @Test
    @DisplayName("기록 저장이 실패하면 결과는 주되 저장된 ID 를 만들었다고 표시하지 않는다")
    void servesUnsavedResultsWhenRecordingFails() {
        // §6.2: 계산된 결과는 미저장 상태로 제공하고 신고·교정은 비활성화한다.
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));
        when(record.complete(any())).thenThrow(new SearchRecordingException("저장 실패"));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.results()).hasSize(1);
        assertThat(result.executionId()).isNull();
        assertThat(result.results())
                .allSatisfy(card -> assertThat(card.searchResultId()).isNull());
        assertThat(result.degradedReasons()).contains("snapshot_save_failed");
    }

    @Test
    @DisplayName("리졸버가 고친 내역을 기록에 넘긴다")
    void handsAnchorFindingsToTheRecord() {
        // findings 는 사람이 읽는 기록이다. 강등 사실 자체는 anchor 의 origin 에 있고,
        // 판정 입력으로는 넘기지 않는다.
        givenResolvedWithUngroundedEntity();
        when(pipeline.rank(any())).thenReturn(candidates());
        when(record.complete(any())).thenReturn(List.of());

        service.execute(query());

        assertThat(recordedResolution().findings())
                .anyMatch(finding -> finding.action().equals("demoted_to_inferred"));
    }

    @Test
    @DisplayName("강등된 anchor 는 추정으로 바뀌어 hard 제외 근거가 되지 않는다")
    void demotedAnchorIsNoLongerExplicit() {
        // hard 제외는 되돌릴 수 없고 그 권한의 유일한 근거가 explicit_query 출처다.
        // 리졸버가 근거 없이 explicit 을 주장해도 검색이 사용자 조건을 위조하면 안 된다.
        givenResolvedWithUngroundedEntity();
        when(pipeline.rank(any())).thenReturn(candidates());
        when(record.complete(any())).thenReturn(List.of());

        service.execute(query());

        assertThat(pipelineQuery().finalResolution().entities())
                .singleElement()
                .satisfies(entity -> assertThat(entity.origin()).isEqualTo(QueryResolution.Origin.INFERRED));
    }

    @Test
    @DisplayName("guard 가 걷어낸 건수와 사유만 요약에 싣는다")
    void summarizesOnlyExcludedGuardVerdicts() {
        // 통과한 장면의 판정까지 실으면 화면이 존재하지 않는 충돌을 띄운다 (§5.1).
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidatesWithGuard(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.guardSummary().excludedResultCount()).isEqualTo(1);
        assertThat(result.guardSummary().reasons()).containsExactly("explicit_date_conflict");
    }

    @Test
    @DisplayName("걷어낸 것이 없으면 guard 요약도 비어 있다")
    void guardSummaryIsEmptyWhenNothingExcluded() {
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.guardSummary().excludedResultCount()).isZero();
        assertThat(result.guardSummary().reasons()).isEmpty();
    }

    @Test
    @DisplayName("10개를 못 채우면 그 이유를 싣는다")
    void reportsShortageReasons() {
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidatesWithShortage(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        assertThat(service.execute(query()).shortageReasons()).containsExactly("candidate_pool_exhausted");
    }

    private RankSearchCandidatesUseCase.Query pipelineQuery() {
        ArgumentCaptor<RankSearchCandidatesUseCase.Query> captor =
                ArgumentCaptor.forClass(RankSearchCandidatesUseCase.Query.class);
        verify(pipeline, atLeastOnce()).rank(captor.capture());
        return captor.getValue();
    }

    private static ExecuteSearchQuery.DateFilters broadcastFilter() {
        return new ExecuteSearchQuery.DateFilters(LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 16), null, null);
    }

    private void givenResolverUnavailable() {
        when(resolver.resolve(RAW_QUERY))
                .thenReturn(new QueryResolutionResult(
                        normalization(),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        QueryResolverErrorCode.RESOLVER_TIMEOUT));
    }

    private void givenResolvedWithUngroundedEntity() {
        QueryResolution claimed = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(new QueryResolution.Entity(
                        QueryResolution.EntityType.PERSON,
                        "원문에없는사람",
                        QueryResolution.Origin.EXPLICIT_QUERY,
                        new QueryResolution.QuerySpan(0, 3),
                        0.8)),
                List.of(),
                List.of(),
                List.of(),
                0.9);
        when(resolver.resolve(RAW_QUERY))
                .thenReturn(new QueryResolutionResult(
                        normalization(), claimed, List.of(), null, "query-resolver/v2", "prompt/v1", "model/v1", null));
    }

    private static QueryNormalization normalization() {
        return new QueryNormalization(RAW_QUERY, List.of("설", "연휴", "서울역", "귀성", "인파"), "norm/v1");
    }

    private SearchCandidates candidatesWithGuard(SearchCandidates.ScoredScene... scenes) {
        SearchCandidates base = candidates(scenes);
        return new SearchCandidates(
                base.scenes(),
                base.candidates(),
                new FalseHitGuardResult(
                        List.of(),
                        List.of(new FalseHitGuardResult.SceneVerdict(
                                9999L, GuardExclusionReason.EXPLICIT_DATE_CONFLICT, List.of())),
                        false),
                base.appliedExcludes(),
                base.config(),
                base.degradedReasons(),
                base.shortageReasons(),
                base.searchTokens(),
                base.expandedTokens());
    }

    private SearchCandidates candidatesWithShortage(SearchCandidates.ScoredScene... scenes) {
        SearchCandidates base = candidates(scenes);
        return new SearchCandidates(
                base.scenes(),
                base.candidates(),
                base.guard(),
                base.appliedExcludes(),
                base.config(),
                base.degradedReasons(),
                List.of(ShortageReason.CANDIDATE_POOL_EXHAUSTED),
                base.searchTokens(),
                base.expandedTokens());
    }

    /** 마지막으로 잰 경로만 기억한다. mock 으로 하면 태그 확인에 verify 가 필요해 테스트가 길어진다. */
    private static final class RecordingTimer implements SearchDurationTimer {
        private SearchPath lastPath;

        @Override
        public void record(long duration, java.util.concurrent.TimeUnit unit, SearchPath path) {
            this.lastPath = path;
        }
    }

    /** 조건이 늘 맞는 규칙. intent 를 바꾸므로 적용되면 최종 해석에서 보인다. */
    private static ParseRule intentRule() {
        return new ParseRule(
                301L,
                ParseRule.SYNTAX_VERSION,
                "query-resolver/v2",
                new ParseRule.Condition(List.of(new ParseRule.Condition.Predicate(
                        ResolutionAxis.INTENT, ParseRule.Condition.Op.EQUALS, null, "scene_search"))),
                new ParseRule.Patch(List.of(new ParseRule.Patch.Operation(
                        ParseRule.Patch.Op.SET,
                        ResolutionAxis.INTENT,
                        new ParseRule.Patch.Target(null, "recent_scene", null, null),
                        null))),
                "{\"rule\":301}",
                null);
    }

    private RecordSearchExecutionResolution recordedResolution() {
        ArgumentCaptor<RecordSearchExecutionResolution> captor =
                ArgumentCaptor.forClass(RecordSearchExecutionResolution.class);
        verify(record, atLeastOnce()).recordResolution(captor.capture());
        return captor.getValue();
    }

    private CompleteSearchExecution completedRecord() {
        ArgumentCaptor<CompleteSearchExecution> captor = ArgumentCaptor.forClass(CompleteSearchExecution.class);
        verify(record, atLeastOnce()).complete(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("검색이 실패하면 열어 둔 실행을 실패로 닫는다")
    void closesTheExecutionWhenTheSearchFails() {
        // 안 닫으면 running 행이 영구히 남아 성공 기록과 구분되지 않는다
        // (§6.2 「성공 기록이 남았다고 주장하지 않는다」).
        givenResolved();
        when(pipeline.rank(any())).thenThrow(new IllegalStateException("색인 접근 실패"));

        assertThatThrownBy(() -> service.execute(query())).isInstanceOf(BusinessException.class);

        verify(record).fail(eq(700L), eq(SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED.code()), anyInt());
    }

    @Test
    @DisplayName("실패를 기록하다 또 실패해도 원래 사유를 가리지 않는다")
    void recordingFailureDoesNotMaskTheOriginalCause() {
        givenResolved();
        when(pipeline.rank(any())).thenThrow(new IllegalStateException("색인 접근 실패"));
        org.mockito.Mockito.doThrow(new SearchRecordingException("기록 실패"))
                .when(record)
                .fail(anyLong(), any(), anyInt());

        assertThatThrownBy(() -> service.execute(query()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED);
    }

    @Test
    @DisplayName("승인된 장면 제외도 guard 요약에 센다")
    void countsApprovedSceneExclusionsInTheGuardSummary() {
        // §5.1 이 approved_scene_exclusion 을 허용 사유로 들어 두었다. guard 판정만 세면
        // 그 수가 실제로 빠진 것보다 적게 나가고 그 사유는 영원히 안 나온다.
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidatesWithApprovedExclusion(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.guardSummary().excludedResultCount()).isEqualTo(1);
        assertThat(result.guardSummary().reasons()).containsExactly("approved_scene_exclusion");
    }

    private SearchCandidates candidatesWithApprovedExclusion(SearchCandidates.ScoredScene... scenes) {
        SearchCandidates base = candidates(scenes);
        return new SearchCandidates(
                base.scenes(),
                base.candidates(),
                base.guard(),
                List.of(new ActiveSceneExclusionResult.ExcludedScene(9999L, List.of(301L))),
                base.config(),
                base.degradedReasons(),
                base.shortageReasons(),
                base.searchTokens(),
                base.expandedTokens());
    }

    @Test
    @DisplayName("규칙이 충돌해 건너뛰면 검색이 죽지 않고 degraded 로 닫힌다")
    void ruleConflictClosesTheExecutionAsDegraded() {
        // CompleteSearchExecution 은 applied_rules 의 skipped_conflict·skipped_incompatible·failed
        // 도 degraded 로 본다. 조립이 SearchDegradedReason 만 보고 SUCCEEDED 로 닫으면 생성자가
        // 불변식 위반으로 던지고, 그 예외는 BusinessException 이 아니라 실행도 안 닫힌다.
        givenResolved();
        when(parseRules.findActivePatchParseRules()).thenReturn(List.of(intentRule(), conflictingIntentRule()));
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(completedRecord().status()).isEqualTo(CompleteSearchExecution.ExecutionStatus.DEGRADED);
        // 규칙 충돌은 기록의 상태를 바꾸지만 공개 어휘에는 없다 (§5.1 이 세 값으로 닫아 두었다).
        assertThat(result.degradedReasons()).isEmpty();
    }

    @Test
    @DisplayName("확장어를 단어 검색에 넘긴다")
    void handsExpandedTermsToTheLexicalChannel() {
        // expanded_terms 는 동의어 검색의 유일한 경로다 (F-04 가 F-05 로 위임). 넘기지 않으면
        // 확장어를 바꾸는 승인 규칙이 기록상 적용돼도 결과에 영향이 없다.
        givenResolvedWithExpandedTerms();
        when(pipeline.rank(any())).thenReturn(candidates());
        when(record.complete(any())).thenReturn(List.of());

        service.execute(query());

        assertThat(pipelineQuery().finalResolution().expandedTerms()).containsExactly("집중호우");
    }

    @Test
    @DisplayName("교정 전 AI 해석을 검증본과 따로 남긴다")
    void keepsTheRawResolutionSeparateFromTheVerifiedOne() {
        // §7.2: resolver_output_json 은 교정 전 AI 해석이다. 검증본만 남기면 리졸버가 실제로
        // 무엇을 주장했는지 사라져 리졸버 품질을 나중에 따져볼 수 없다.
        givenResolvedWithUngroundedEntity();
        when(pipeline.rank(any())).thenReturn(candidates());
        when(record.complete(any())).thenReturn(List.of());

        service.execute(query());

        var output = recordedResolution().resolverOutput();
        assertThat(output.rawResolution().entities())
                .singleElement()
                .satisfies(entity -> assertThat(entity.origin()).isEqualTo(QueryResolution.Origin.EXPLICIT_QUERY));
        assertThat(output.verifiedResolution().entities())
                .singleElement()
                .satisfies(entity -> assertThat(entity.origin()).isEqualTo(QueryResolution.Origin.INFERRED));
    }

    @Test
    @DisplayName("해석 기록이 실패하면 complete 를 부르지 않고 실행을 닫는다")
    void closesTheExecutionWhenTheResolutionSnapshotFails() {
        // 어댑터는 resolution_recorded=false 인 실행의 완료를 거부한다. 그래도 complete 를 부르면
        // 두 번째 실패가 또 삼켜지고 행이 영영 running 으로 남는다.
        givenResolved();
        org.mockito.Mockito.doThrow(new SearchRecordingException("해석 저장 실패"))
                .when(record)
                .recordResolution(any());
        when(pipeline.rank(any())).thenReturn(candidates(scene(9301, 9101)));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.results()).hasSize(1);
        assertThat(result.executionId()).isNull();
        assertThat(result.degradedReasons()).containsExactly("snapshot_save_failed");
        verify(record, never()).complete(any());
        verify(record).fail(eq(700L), any(), anyInt());
    }

    /** 같은 축을 다르게 바꾸는 규칙. intentRule 과 함께 두면 충돌 그룹이 생겨 둘 다 건너뛴다. */
    private static ParseRule conflictingIntentRule() {
        return new ParseRule(
                302L,
                ParseRule.SYNTAX_VERSION,
                "query-resolver/v2",
                new ParseRule.Condition(List.of(new ParseRule.Condition.Predicate(
                        ResolutionAxis.INTENT, ParseRule.Condition.Op.EQUALS, null, "scene_search"))),
                new ParseRule.Patch(List.of(new ParseRule.Patch.Operation(
                        ParseRule.Patch.Op.SET,
                        ResolutionAxis.INTENT,
                        new ParseRule.Patch.Target(null, "unknown", null, null),
                        null))),
                "{\"rule\":302}",
                null);
    }

    private void givenResolvedWithExpandedTerms() {
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
        when(resolver.resolve(RAW_QUERY))
                .thenReturn(new QueryResolutionResult(
                        normalization(), withTerms, List.of(), null, "query-resolver/v2", "p", "m", null));
    }

    @Test
    @DisplayName("규칙 조회 DB 실패를 해석 규칙 전용 코드로 올린다")
    void translatesRuleLookupFailureToItsOwnCode() {
        // 어댑터는 해석 규칙과 장면 제외 규칙에 같은 SRCH_503_201 을 쓴다. 그대로 올리면 계약표가
        // 장면 제외 전용으로 적어 둔 코드가 해석 규칙 실패에도 나가, FE 안내가 엉뚱해진다.
        givenResolved();
        when(parseRules.findActivePatchParseRules())
                .thenThrow(new BusinessException(SearchRuleErrorCode.RULE_LOOKUP_FAILED));

        assertThatThrownBy(() -> service.execute(query()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(SearchExecutionErrorCode.ACTIVE_RULE_LOOKUP_FAILED);
    }

    @Test
    @DisplayName("확장어로 걸린 장면도 근거에 그 단어를 싣는다")
    void reportsExpandedTermsAsEvidence() {
        // 검색에는 쓰고 설명에는 안 쓰면 확장어로만 걸린 장면의 matched_keywords 가 비어
        // "왜 나왔는지 모르는 결과" 가 된다.
        givenResolved();
        SearchCandidates base = candidates(sceneWithCaption("집중호우 피해", List.of("집중호우", "피해")));
        when(pipeline.rank(any()))
                .thenReturn(new SearchCandidates(
                        base.scenes(),
                        base.candidates(),
                        base.guard(),
                        base.appliedExcludes(),
                        base.config(),
                        base.degradedReasons(),
                        base.shortageReasons(),
                        base.searchTokens(),
                        List.of("집중호우")));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        // 확장어로만 걸린 말이므로 origin 은 expanded 다 — 사용자가 친 말로 표시하면 F-05 구분이 깨진다.
        assertThat(result.results().getFirst().matchedKeywords())
                .contains(new SearchExecutionResult.MatchedKeyword(
                        "집중호우", SearchExecutionResult.MatchedKeyword.ORIGIN_EXPANDED));
    }

    @Test
    @DisplayName("원 질의 키워드는 정규화 결과가 아니라 후보 조회가 실제로 건 토큰에서 고른다")
    void matchedKeywordsUseTheTokensTheLexicalQueryActuallyUsed() {
        // S15P21A501-320: 범용어(장면·화면 등)는 BM25 질의에서 빠진다. 정규화 결과로 대조하면 캡션 셋 중 하나에
        // 있는 그 말이 칩으로 떠서, 조회에 쓰지도 않은 말 때문에 나온 것처럼 보인다. 여기서는 「연휴」 를 뺀 값을 준다.
        givenResolved();
        SearchCandidates base = candidates(sceneWithCaption("연휴 서울역", List.of("연휴", "서울역")));
        when(pipeline.rank(any()))
                .thenReturn(new SearchCandidates(
                        base.scenes(),
                        base.candidates(),
                        base.guard(),
                        base.appliedExcludes(),
                        base.config(),
                        base.degradedReasons(),
                        base.shortageReasons(),
                        List.of("설", "서울역", "귀성", "인파"),
                        List.of()));
        when(record.complete(any())).thenReturn(List.of(801L));

        SearchExecutionResult result = service.execute(query());

        assertThat(result.results().getFirst().matchedKeywords())
                .containsExactly(new SearchExecutionResult.MatchedKeyword(
                        "서울역", SearchExecutionResult.MatchedKeyword.ORIGIN_USER));
    }

    private SearchCandidates.ScoredScene sceneWithCaption(String caption, List<String> captionTokens) {
        SearchCandidates.ScoredScene base = scene(9301, 9101);
        return new SearchCandidates.ScoredScene(
                base.sceneId(),
                base.clipId(),
                new SceneCard(
                        9301,
                        9101,
                        "KBC 뉴스9",
                        caption,
                        42000,
                        49000,
                        "b_roll",
                        captionTokens,
                        null,
                        List.of(),
                        List.of()),
                base.tags(),
                base.score(),
                base.soft(),
                base.verdict());
    }

    @Test
    @DisplayName("해석 구간은 기록을 하나도 건드리지 않는다")
    void interpretTouchesNoRecord() {
        // 후보 검증 재검색(-83)은 롤백 트랜잭션 안에서 이 구간을 태운다. 기록 호출이 하나라도
        // 섞이면 REQUIRES_NEW 라 롤백 밖에 커밋돼, 롤백돼야 할 검증 검색이 original 실행 행을
        // 남기고 일반 검색·평가 집계에 섞인다 (F-08 격리).
        givenResolved();

        InterpretedQuery interpreted = service.interpret(query());

        assertThat(interpreted.finalResolution()).isNotNull();
        assertThat(interpreted.parseSource()).isEqualTo(StartSearchExecution.ParseSource.RESOLVER);
        org.mockito.Mockito.verifyNoInteractions(record);
    }

    @Test
    @DisplayName("해석 구간이 낸 값을 검색 경로가 그대로 쓴다")
    void executeReusesTheSameInterpretation() {
        // 두 경로가 다른 코드를 타면 FRD §11 의 「일반 검색과 같은 코드로 검증 검색을 한다」가
        // 깨진다. 같은 질의를 해석 단독으로 돌린 값과 검색 경로가 파이프라인에 넘긴 값이 같아야 한다.
        givenResolved();
        when(pipeline.rank(any())).thenReturn(candidates());
        when(record.complete(any())).thenReturn(List.of());

        InterpretedQuery alone = service.interpret(query());
        service.execute(query());

        assertThat(pipelineQuery().finalResolution().intent())
                .isEqualTo(alone.finalResolution().intent());
        assertThat(pipelineQuery().normalizedSearch().fingerprint())
                .isEqualTo(alone.normalizedSearch().fingerprint());
    }

    private void givenResolved() {
        when(resolver.resolve(RAW_QUERY))
                .thenReturn(new QueryResolutionResult(
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
        return new ExecuteSearchQuery(RAW_QUERY, ExecuteSearchQuery.DateFilters.none(), 9001L);
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
                List.of(),
                normalization().searchTokens(),
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
