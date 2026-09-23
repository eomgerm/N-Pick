package com.npick.search.application.query.search;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateFingerprint;
import com.npick.search.application.error.VerificationErrorCode;
import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.ExcludeContext;
import com.npick.search.application.port.ExcludeContextPort;
import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.StartSearchExecution;
import com.npick.search.application.query.fusion.FuseSearchRankingQuery;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.query.guard.FalseHitGuardResult;
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
import com.npick.search.domain.policy.ParseRulePolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code VerificationSearchService.verify} 의 인가/전제 검사 (fix round 1, S15P21A501-83).
 *
 * <p>{@code CreateSceneExcludeCandidateServiceTest} 와 같은 패턴: 실 DB 없이 포트를 모두 mock 해 가드 순서·오류 코드만 검증한다. 검증은
 * {@link ExcludeContextPort}(형제 {@code CreateSceneExcludeCandidateService}가 쓰는 바로 그 포트)를 재사용해 신고 존재·상태·담당 검수자를 확인하고,
 * {@link PendingCandidatesPort}로 대기 후보 존재를 확인한다. 이 네 가드 중 하나라도 걸리면 {@code inputPort.load}·{@code record.start} 등 뒤 단계는
 * 전혀 호출되지 않는다.
 */
class VerificationSearchServiceTest {

    private static final long FEEDBACK_ID = 1L;

    private ExcludeContextPort excludeContextPort;
    private VerificationInputPort inputPort;
    private PendingCandidatesPort candidatesPort;
    private InterpretSearchQueryUseCase interpreter;
    private RankSearchCandidatesUseCase ranker;
    private SearchExecutionRecordPort record;
    private CorrectionStateFingerprint fingerprint;
    private VerificationCandidateStatePort candidateState;
    private VerificationSearchService service;

    @BeforeEach
    void setUp() {
        excludeContextPort = mock(ExcludeContextPort.class);
        inputPort = mock(VerificationInputPort.class);
        candidatesPort = mock(PendingCandidatesPort.class);
        interpreter = mock(InterpretSearchQueryUseCase.class);
        ranker = mock(RankSearchCandidatesUseCase.class);
        record = mock(SearchExecutionRecordPort.class);
        fingerprint = mock(CorrectionStateFingerprint.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        // 롤백 트랜잭션 템플릿이 콜백에 실제 TransactionStatus 를 넘겨야
        // VerificationCandidateStatePort.flip 뒤의 status.setRollbackOnly() 가 NPE 없이 동작한다.
        when(txManager.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        candidateState = mock(VerificationCandidateStatePort.class);
        service = new VerificationSearchService(
                excludeContextPort,
                inputPort,
                candidatesPort,
                interpreter,
                ranker,
                record,
                fingerprint,
                txManager,
                candidateState);
    }

    private ExcludeContext reviewingContext(Long reviewedById) {
        return new ExcludeContext("REVIEWING", "tag_correction", reviewedById, 300L, "fp-1", "q", "{}", "v1");
    }

    @Test
    @DisplayName("없는 신고면 404 로 거부한다")
    void rejectsMissingFeedback() {
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 9L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.FEEDBACK_NOT_FOUND));
        verify(inputPort, never()).load(FEEDBACK_ID);
        verify(record, never()).start(any());
    }

    @Test
    @DisplayName("검수 중이 아니면 409 로 거부한다")
    void rejectsNotReviewing() {
        when(excludeContextPort.find(FEEDBACK_ID))
                .thenReturn(
                        Optional.of(new ExcludeContext("CLOSED", "tag_correction", 9L, 300L, "fp-1", "q", "{}", "v1")));

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 9L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.NOT_REVIEWING));
        verify(inputPort, never()).load(FEEDBACK_ID);
    }

    @Test
    @DisplayName("담당 검수자가 아니면 403 으로 거부한다")
    void rejectsNonOwnerReviewer() {
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.of(reviewingContext(9L)));

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 7L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.NOT_REVIEWER));
        verify(inputPort, never()).load(FEEDBACK_ID);
    }

    @Test
    @DisplayName("아직 claim 되지 않은(reviewed_by_id 없음) 신고면 403 으로 거부한다")
    void rejectsUnclaimedFeedback() {
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.of(reviewingContext(null)));

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 9L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.NOT_REVIEWER));
    }

    @Test
    @DisplayName("대기 중인 교정 후보가 없으면 409 로 거부한다")
    void rejectsNoPendingCandidates() {
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.of(reviewingContext(9L)));
        when(candidatesPort.load(FEEDBACK_ID))
                .thenReturn(new PendingCandidates("tag_correction", List.of(), List.of()));

        assertThatThrownBy(() -> service.verify(FEEDBACK_ID, 9L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(VerificationErrorCode.NO_PENDING_CANDIDATES));
        verify(inputPort, never()).load(FEEDBACK_ID);
        verify(record, never()).start(any());
    }

    @Test
    @DisplayName("문의의 태그·규칙 대기 후보를 resolution 타입과 무관하게 함께 검증한다")
    @SuppressWarnings("unchecked")
    void verifiesBothTagAndRulePendingCandidatesTogether() {
        // PendingCandidates 는 이미 태그(tag_evidence confirmed=false) + 규칙(search_rule active=false)을
        // 양축 다 담고 있다(어댑터가 resolution 타입으로 거르지 않는다) — 여기서는 VerificationSearchService 가
        // 그 합집합을 그대로 flip·기록·지문 계산에 쓰는지, 한쪽 축만 골라내지 않는지를 잠근다.
        when(excludeContextPort.find(FEEDBACK_ID)).thenReturn(Optional.of(reviewingContext(9L)));
        PendingCandidates candidates = new PendingCandidates(
                "correction",
                List.of(501L),
                List.of(new PendingCandidates.RuleCandidate(601L, null, "exclude_scene")));
        when(candidatesPort.load(FEEDBACK_ID)).thenReturn(candidates);
        when(inputPort.load(FEEDBACK_ID))
                .thenReturn(new VerificationInput("설 연휴 서울역", ExecuteSearchQuery.DateFilters.none()));
        when(inputPort.loadOriginalResultScenes(FEEDBACK_ID)).thenReturn(List.of());
        when(record.start(any())).thenReturn(900L);

        QueryResolutionResult resolved = resolvedResult();
        when(interpreter.resolve(any())).thenReturn(new InterpretSearchQueryUseCase.Resolution(resolved, resolved, 10));
        when(interpreter.interpretFromResolution(any(), any())).thenReturn(interpretedQuery(resolved));
        when(ranker.rank(any())).thenReturn(emptySearchCandidates());
        when(candidateState.readActivePatchRuleIds()).thenReturn(List.of());
        when(fingerprint.compute(eq(FEEDBACK_ID), anyList(), anyList())).thenReturn("fp-mixed");

        service.verify(FEEDBACK_ID, 9L);

        // flip 은 로드된 후보 전체(양축)를 그대로 받는다.
        verify(candidateState).flip(candidates);

        // fingerprint 는 rule/tag 두 축을 모두 포함한 id 로 계산된다.
        ArgumentCaptor<List<Long>> ruleIdsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Long>> tagIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(fingerprint).compute(eq(FEEDBACK_ID), ruleIdsCaptor.capture(), tagIdsCaptor.capture());
        assertThat(ruleIdsCaptor.getValue()).containsExactly(601L);
        assertThat(tagIdsCaptor.getValue()).containsExactly(501L);

        // 검증 실행 기록도 두 축의 승인 id 를 모두 남긴다.
        ArgumentCaptor<CompleteSearchExecution> completeCaptor = ArgumentCaptor.forClass(CompleteSearchExecution.class);
        verify(record).complete(completeCaptor.capture());
        Map<String, Object> context = completeCaptor.getValue().verificationContext();
        assertThat(context.get("approved_evidence_ids")).isEqualTo(List.of(501L));
        assertThat(context.get("approved_rule_action")).isEqualTo("exclude_scene");
        Map<String, Object> verifiedIds = (Map<String, Object>) context.get("verified_candidate_ids");
        assertThat((List<Long>) verifiedIds.get("rule_ids")).containsExactly(601L);
        assertThat((List<Long>) verifiedIds.get("tag_evidence_ids")).containsExactly(501L);
    }

    private static QueryResolutionResult resolvedResult() {
        return new QueryResolutionResult(
                new QueryNormalization("설 연휴 서울역", List.of("설", "연휴", "서울역"), "norm/v1"),
                resolution(),
                List.of(),
                null,
                "query-resolver/v2",
                "prompt/v1",
                "model/v1",
                null);
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

    private static InterpretedQuery interpretedQuery(QueryResolutionResult resolved) {
        return new InterpretedQuery(
                resolved,
                resolved,
                10,
                new ParseRulePolicy.Result(resolution(), List.of()),
                false,
                StartSearchExecution.ParseSource.RESOLVER,
                resolution(),
                NormalizedSearch.of("설 연휴 서울역", Map.of(), "norm/v1"),
                List.of());
    }

    private static SearchCandidates emptySearchCandidates() {
        var structured = new StructuredScoresResult(resolution(), structuredSettings(), List.of(), List.of());
        return new SearchCandidates(
                List.of(),
                new FuseSearchRankingQuery(List.of(), null, structured),
                new FalseHitGuardResult(List.of(), List.of(), false),
                List.of(),
                new SearchConfigSnapshot(
                        fusionSettings(),
                        new LexicalSearchSettings("candidate-v1", 1.0, 1.0, 1.0, 0.3, 200),
                        null,
                        structuredSettings(),
                        softSettings()),
                List.of(),
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
}
