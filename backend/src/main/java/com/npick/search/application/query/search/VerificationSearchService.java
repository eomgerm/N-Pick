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
import com.npick.search.application.error.VerificationErrorCode;
import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.ExcludeContext;
import com.npick.search.application.port.ExcludeContextPort;
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
 *
 * <p><b>요청당 커넥션 최대 2개, 순차 사용(§8, -176)</b>: 외부 롤백 트랜잭션(1, {@link #rollbackTemplate})이 끝난 뒤에야
 * {@code REQUIRES_NEW} 기록(1, {@link SearchExecutionRecordPort})이 시작된다 — 둘은 겹치지 않으므로 한 {@code verify()}
 * 호출 안에서 동시에 열리는 커넥션은 최대 1개다. 그래도 검증 동시성 상한 또는 커넥션 풀 크기는 <b>동시 요청 수 × 2</b> 이상을
 * 보수적으로 잡아 둔다. 테스트 환경({@code NpickPostgres})은 풀 크기 3 에 테스트가 직렬 실행이라 이 권장치에 걸리지 않는다.
 */
@Service
public class VerificationSearchService implements VerifyCorrectionCandidatesUseCase {

    private final ExcludeContextPort excludeContextPort;
    private final VerificationInputPort inputPort;
    private final PendingCandidatesPort candidatesPort;
    private final InterpretSearchQueryUseCase interpreter;
    private final RankSearchCandidatesUseCase ranker;
    private final SearchExecutionRecordPort record;
    private final CorrectionStateFingerprint fingerprint;
    private final TransactionTemplate rollbackTemplate;
    private final EntityManager em;

    public VerificationSearchService(
            ExcludeContextPort excludeContextPort,
            VerificationInputPort inputPort,
            PendingCandidatesPort candidatesPort,
            InterpretSearchQueryUseCase interpreter,
            RankSearchCandidatesUseCase ranker,
            SearchExecutionRecordPort record,
            CorrectionStateFingerprint fingerprint,
            PlatformTransactionManager txManager,
            EntityManager em) {
        this.excludeContextPort = excludeContextPort;
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

    /**
     * 담당 검수자·신고 상태·대기 후보 존재를 확인한다 — {@code CreateSceneExcludeCandidateService}(§6.4 장면 제외 후보)와
     * 같은 전제·같은 포트({@link ExcludeContextPort}, feedback→search_result→search_execution 조인)를 그대로 재사용한다.
     * 검증은 resolution 종류(tag_correction/patch_parse/exclude_scene)를 가리지 않으므로 그 형제의
     * {@code NOT_EXCLUDE_SCENE} 같은 처리결과별 체크 대신 「대기 후보가 하나라도 있는가」를 마지막에 본다.
     * 이 존재 확인이 끝난 뒤에야 {@link VerificationInputPort#load}를 호출하므로, 신고가 없을 때 그 어댑터의
     * {@code getSingleResult}가 {@code NoResultException}으로 500을 내는 경로를 막는다.
     */
    @Override
    public VerificationResult verify(long feedbackId, long reviewerId) {
        ExcludeContext context = excludeContextPort
                .find(feedbackId)
                .orElseThrow(() -> new BusinessException(VerificationErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(VerificationErrorCode.NOT_REVIEWING);
        }
        if (context.reviewedById() == null || context.reviewedById() != reviewerId) {
            throw new BusinessException(VerificationErrorCode.NOT_REVIEWER);
        }
        PendingCandidates candidates = candidatesPort.load(feedbackId);
        if (candidates.tagEvidenceIds().isEmpty() && candidates.approvedRuleId() == null) {
            throw new BusinessException(VerificationErrorCode.NO_PENDING_CANDIDATES);
        }

        VerificationInput input = inputPort.load(feedbackId);
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
            List<Long> originalSceneIds = inputPort.loadOriginalResultSceneIds(feedbackId);
            SceneDiff.Result diff = SceneDiff.of(originalSceneIds, outcome.candidates());
            return new VerificationResult(executionId, diff.entered(), diff.dropped(), outcome.activeRuleSet());
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
        context.put("verification_rule_set", outcome.activeRuleSet()); // §7.2 검증 규칙 집합 (활성 − R1 + R2)
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

    /**
     * {@link SearchAssemblyService#abandon} 과 같은 분류 — BusinessException 은 자기 코드를, 그 외는 기본값을 남긴다.
     * 기본값은 {@code SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED} 가 아니라 {@link VerificationErrorCode#VERIFICATION_FAILED}
     * 다 — 그쪽은 단어 검색 전용이라 랭커 내부 오류·기록 커밋 실패 같은 검증 전반의 실패에 붙이면 사유가 틀리게 남는다(Task 4 리뷰 지적).
     */
    private String verificationErrorCode(RuntimeException failed) {
        return failed instanceof BusinessException business
                ? business.errorCode().code()
                : VerificationErrorCode.VERIFICATION_FAILED.code();
    }

    private int elapsedMs(long startedAtNanos) {
        return (int) ((System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    /**
     * 롤백 트랜잭션 안에서 검색 결과와 해석을 함께 캡처한다. 검색은 롤백되지만 이 값은 트랜잭션 밖으로
     * 나가 replay 기록에 쓰인다 — 일반 검색 기록과 같은 충실도(finalResolution·degradedReasons·
     * rules().outcomes())를 남기기 위해 {@code SearchCandidates} 만이 아니라 {@code InterpretedQuery} 도 담는다.
     */
    record VerificationSearchOutcome(SearchCandidates candidates, InterpretedQuery interpreted, List<Long> activeRuleSet) {}

    /** flip → 같은 코드로 재검색 → 캡처 → 롤백. 공유·확정 데이터는 복구된다 (FRD §11). */
    private VerificationSearchOutcome searchWithCandidatesRolledBack(
            ExecuteSearchQuery query, PendingCandidates candidates) {
        return rollbackTemplate.execute(status -> {
            flip(candidates);
            InterpretedQuery iq = interpreter.interpret(query);
            SearchCandidates result = ranker.rank(new RankSearchCandidatesUseCase.Query(
                    iq.resolved().normalization(), iq.finalResolution(),
                    iq.resolved().queryEmbedding(), iq.normalizedSearch()));
            List<Long> activeRuleSet = readActivePatchRuleIds(); // flip 반영 상태 = 활성 − R1 + R2
            status.setRollbackOnly(); // flip 과 후보 적용을 모두 되돌린다
            return new VerificationSearchOutcome(result, iq, activeRuleSet);
        });
    }

    /**
     * flip 이 반영된 상태의 활성 patch_parse 규칙 집합 — 「활성 − R1 + R2」 조합 그 자체다. Task 1 지문(all-active,
     * action 무필터)과는 목적이 다르다: 여기는 조합 검증용으로 action='patch_parse' 만 본다.
     */
    @SuppressWarnings("unchecked")
    private List<Long> readActivePatchRuleIds() {
        List<Number> rows = em.createNativeQuery(
                        "SELECT search_rule_id FROM npick.search_rule "
                                + "WHERE action = 'patch_parse' AND active = true ORDER BY search_rule_id")
                .getResultList();
        return rows.stream().map(Number::longValue).toList();
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
