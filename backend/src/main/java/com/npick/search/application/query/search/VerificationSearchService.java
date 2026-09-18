package com.npick.search.application.query.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateFingerprint;
import com.npick.search.application.error.SearchExecutionErrorCode;
import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.RecordSearchExecutionResolution;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.StartSearchExecution;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ParseRuleOutcome;

/**
 * 후보 검증 자동 재검색 오케스트레이션 (S15P21A501-83, FRD F-12·§11).
 *
 * <p>같은 패키지에 두는 이유: {@link SearchRecordPayload}·{@link SearchExplain} 이 package-private 이고, 검증 검색은
 * 일반 검색과 <b>같은 기록 형태</b>를 남겨야 하므로(FRD §11) 그 변환을 재사용해야 한다.
 */
@Service
public class VerificationSearchService implements VerifyCorrectionCandidatesUseCase {

    private final VerificationInputPort inputPort;
    private final PendingCandidatesPort candidatesPort;
    private final InterpretSearchQueryUseCase interpreter;
    private final RankSearchCandidatesUseCase ranker;
    private final SearchExecutionRecordPort record;
    private final CorrectionStateFingerprint fingerprint;
    private final TransactionTemplate rollbackTemplate;
    private final EntityManager em;

    public VerificationSearchService(
            VerificationInputPort inputPort,
            PendingCandidatesPort candidatesPort,
            InterpretSearchQueryUseCase interpreter,
            RankSearchCandidatesUseCase ranker,
            SearchExecutionRecordPort record,
            CorrectionStateFingerprint fingerprint,
            PlatformTransactionManager txManager,
            EntityManager em) {
        this.inputPort = inputPort;
        this.candidatesPort = candidatesPort;
        this.interpreter = interpreter;
        this.ranker = ranker;
        this.record = record;
        this.fingerprint = fingerprint;
        this.em = em;
        this.rollbackTemplate = new TransactionTemplate(txManager);
        this.rollbackTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public VerificationResult verify(long feedbackId, long reviewerId) {
        VerificationInput input = inputPort.load(feedbackId);
        PendingCandidates candidates = candidatesPort.load(feedbackId);
        ExecuteSearchQuery query = new ExecuteSearchQuery(input.rawQuery(), input.dateFilters(), reviewerId);

        String baselineFingerprint = fingerprint.compute(feedbackId); // flip 전 기준 상태
        long executionId = record.start(new StartSearchExecution(
                reviewerId, StartSearchExecution.ExecutionType.REPLAY, feedbackId, input.rawQuery()));
        long startedAt = System.nanoTime();
        try {
            VerificationSearchOutcome outcome = searchWithCandidatesRolledBack(query, candidates);
            // complete() 는 해석 스냅샷(normalized_query 등)이 먼저 채워져 있어야 완결을 받아준다 — 일반 검색과
            // 같은 두 단계(recordResolution → complete)를 그대로 태운다. 롤백은 이미 끝났으므로 여기서 쓰는 값은
            // 롤백 전에 캡처해 둔 InterpretedQuery 그대로다.
            record.recordResolution(recordResolutionCommand(executionId, query, outcome.interpreted()));
            record.complete(completeCommand(executionId, outcome, candidates, baselineFingerprint, startedAt));
            return new VerificationResult(executionId);
        } catch (RuntimeException failed) {
            // running 누수 금지 (§8, -59 H5 패턴): 넓은 catch 로 실행 행을 fail 로 닫는다.
            record.fail(executionId, verificationErrorCode(failed), elapsedMs(startedAt));
            throw failed;
        }
    }

    /**
     * 일반 검색 {@code SearchAssemblyService.completeRecord} 와 같은 조립: 해석 단계 사유 + 후보 구간 사유를 합치고, 규칙
     * degraded 여부까지 반영해 status 를 매핑한다. {@code verificationContext} 는 -84 {@code VerificationRunQueryAdapter} 의
     * 엄격 계약(§10)을 채운다.
     */
    private CompleteSearchExecution completeCommand(
            long executionId, VerificationSearchOutcome outcome, PendingCandidates candidates,
            String baselineFingerprint, long startedAt) {
        SearchCandidates result = outcome.candidates();
        InterpretedQuery interpreted = outcome.interpreted();
        List<String> queryTokens = result.expandedTokens(); // 검증 카드는 원 질의 토큰 확장을 그대로 쓴다

        List<ParseRuleOutcome> appliedRules = interpreted.rules().outcomes();
        List<SearchDegradedReason> degradedReasons = new ArrayList<>(interpreted.degradedReasons());
        degradedReasons.addAll(result.degradedReasons());
        boolean ruleDegraded = appliedRules.stream().anyMatch(rule -> rule.status().degraded());
        CompleteSearchExecution.ExecutionStatus status = degradedReasons.isEmpty() && !ruleDegraded
                ? CompleteSearchExecution.ExecutionStatus.SUCCEEDED
                : CompleteSearchExecution.ExecutionStatus.DEGRADED;

        var context = new LinkedHashMap<String, Object>();
        context.put("resolution", candidates.resolution());
        context.put("approved_evidence_ids", candidates.tagEvidenceIds());
        context.put("approved_rule_id", candidates.approvedRuleId());
        context.put("replaced_rule_id", candidates.replacedRuleId());
        context.put("state_fingerprint", baselineFingerprint);
        context.put("candidate_tag_changes", candidates.tagEvidenceIds()); // §7.2 후보 태그 변경안
        context.put("baseline_state", Map.of("state_fingerprint", baselineFingerprint)); // §7.2 기준 상태
        context.put("verification_rule_set", verificationRuleSet(candidates)); // §7.2 검증 규칙 집합
        return new CompleteSearchExecution(
                executionId,
                status,
                degradedReasons, null,
                interpreted.finalResolution(),  // 검증이 실제로 쓴 해석 — replay 행도 일반 검색과 같은 충실도
                appliedRules,
                SearchRecordPayload.candidates(result),
                SearchRecordPayload.filtered(result),
                SearchRecordPayload.appliedExcludes(result),
                SearchRecordPayload.rankedScenes(result, queryTokens),
                result.config(),
                elapsedMs(startedAt),
                context);
    }

    /** {@code SearchAssemblyService.resolverOutput} 과 같은 조립. AI 가 실제로 무엇을 주장했는지(raw) 와 anchor 검증 후(verified) 를 함께 남긴다. */
    private RecordSearchExecutionResolution recordResolutionCommand(
            long executionId, ExecuteSearchQuery query, InterpretedQuery interpreted) {
        return new RecordSearchExecutionResolution(
                executionId,
                query.explicitFilters(),
                interpreted.normalizedSearch(),
                resolverOutput(interpreted),
                interpreted.resolved().findings(),
                interpreted.parseSource(),
                interpreted.parseMs(),
                interpreted.degradedReasons());
    }

    private RecordSearchExecutionResolution.ResolverOutput resolverOutput(InterpretedQuery interpreted) {
        QueryResolutionResult resolved = interpreted.resolved();
        if (!resolved.isResolved()) {
            return null;
        }
        return new RecordSearchExecutionResolution.ResolverOutput(
                interpreted.raw().resolution(),
                resolved.resolution(),
                resolved.resolutionSchemaVersion(),
                resolved.promptVersion(),
                resolved.modelVersion());
    }

    /** 「활성 − R1 + R2」 규칙 id 집합의 최소 구현 — approvedRuleId 를 담은 목록이다 (조합 계산은 Task 5 에서 확정). */
    private List<Long> verificationRuleSet(PendingCandidates candidates) {
        return candidates.approvedRuleId() == null ? List.of() : List.of(candidates.approvedRuleId());
    }

    /** {@link SearchAssemblyService#abandon} 과 같은 분류 — BusinessException 은 자기 코드를, 그 외는 기본 검색 실패 코드를 남긴다. */
    private String verificationErrorCode(RuntimeException failed) {
        return failed instanceof BusinessException business
                ? business.errorCode().code()
                : SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED.code();
    }

    private int elapsedMs(long startedAtNanos) {
        return (int) ((System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    /**
     * 롤백 트랜잭션 안에서 검색 결과와 해석을 함께 캡처한다. 검색은 롤백되지만 이 값은 트랜잭션 밖으로
     * 나가 replay 기록에 쓰인다 — 일반 검색 기록과 같은 충실도(finalResolution·degradedReasons·
     * rules().outcomes())를 남기기 위해 {@code SearchCandidates} 만이 아니라 {@code InterpretedQuery} 도 담는다.
     */
    record VerificationSearchOutcome(SearchCandidates candidates, InterpretedQuery interpreted) {}

    /** flip → 같은 코드로 재검색 → 캡처 → 롤백. 공유·확정 데이터는 복구된다 (FRD §11). */
    private VerificationSearchOutcome searchWithCandidatesRolledBack(
            ExecuteSearchQuery query, PendingCandidates candidates) {
        return rollbackTemplate.execute(status -> {
            flip(candidates);
            InterpretedQuery iq = interpreter.interpret(query);
            SearchCandidates result = ranker.rank(new RankSearchCandidatesUseCase.Query(
                    iq.resolved().normalization(), iq.finalResolution(),
                    iq.resolved().queryEmbedding(), iq.normalizedSearch()));
            status.setRollbackOnly(); // flip 과 후보 적용을 모두 되돌린다
            return new VerificationSearchOutcome(result, iq);
        });
    }

    private void flip(PendingCandidates c) {
        if (!c.tagEvidenceIds().isEmpty()) {
            em.createNativeQuery("UPDATE npick.tag_evidence SET confirmed = true WHERE evidence_id IN (:ids)")
                    .setParameter("ids", c.tagEvidenceIds())
                    .executeUpdate();
        }
        if (c.approvedRuleId() != null) {
            em.createNativeQuery("UPDATE npick.search_rule SET active = true WHERE search_rule_id = :r2")
                    .setParameter("r2", c.approvedRuleId()).executeUpdate();
        }
        if (c.replacedRuleId() != null) {
            em.createNativeQuery("UPDATE npick.search_rule SET active = false WHERE search_rule_id = :r1")
                    .setParameter("r1", c.replacedRuleId()).executeUpdate();
        }
    }
}
