package com.npick.search.application.query.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateFingerprint;
import com.npick.search.application.error.ParseRuleCandidateErrorCode;
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
 * <p>같은 패키지에 두는 이유: {@link SearchRecordPayload}·{@link SearchExplain} 이 package-private 이고, 검증 검색은 일반 검색과 <b>같은 기록
 * 형태</b>를 남겨야 하므로(FRD §11) 그 변환을 재사용해야 한다.
 *
 * <p><b>요청당 커넥션 최대 2개, 순차 사용(§8, -176)</b>: {@code REQUIRES_NEW} 실행 시작 기록, 외부 롤백 트랜잭션(1, {@link #rollbackTemplate}),
 * {@code REQUIRES_NEW} 완료 기록은 순차로 실행된다. 한 {@code verify()} 호출 안에서 동시에 열리는 커넥션은 최대 1개다. 그래도 검증 동시성 상한 또는 커넥션 풀 크기는 <b>동시
 * 요청 수 × 2</b> 이상을 보수적으로 잡아 둔다. 테스트 환경({@code NpickPostgres})은 풀 크기 3 에 테스트가 직렬 실행이라 이 권장치에 걸리지 않는다.
 *
 * <p><b>AI 리졸버 호출은 롤백 트랜잭션 밖에서 먼저 끝낸다(S15P21A501-219)</b>: {@link InterpretSearchQueryUseCase#resolve} 를 트랜잭션을 열기 전에
 * 부르고, 트랜잭션 안에서는 {@link InterpretSearchQueryUseCase#interpretFromResolution} 만 부른다 — 활성 규칙 조회가 flip 반영 상태를 읽어야 해서 그 부분만
 * 트랜잭션 안에 남는다. 리졸버 응답 지연이 후보 행 잠금·커넥션 점유 시간에 더해지지 않는다.
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
    private final VerificationCandidateStatePort candidateState;

    public VerificationSearchService(
            ExcludeContextPort excludeContextPort,
            VerificationInputPort inputPort,
            PendingCandidatesPort candidatesPort,
            InterpretSearchQueryUseCase interpreter,
            RankSearchCandidatesUseCase ranker,
            SearchExecutionRecordPort record,
            CorrectionStateFingerprint fingerprint,
            PlatformTransactionManager txManager,
            VerificationCandidateStatePort candidateState) {
        this.excludeContextPort = excludeContextPort;
        this.inputPort = inputPort;
        this.candidatesPort = candidatesPort;
        this.interpreter = interpreter;
        this.ranker = ranker;
        this.record = record;
        this.fingerprint = fingerprint;
        this.candidateState = candidateState;
        this.rollbackTemplate = new TransactionTemplate(txManager);
        this.rollbackTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 담당 검수자·신고 상태·대기 후보 존재를 확인한다 — {@code CreateSceneExcludeCandidateService}(§6.4 장면 제외 후보)와 같은 전제·같은
     * 포트({@link ExcludeContextPort}, feedback→search_result→search_execution 조인)를 그대로 재사용한다. 검증은 resolution
     * 종류(tag_correction/patch_parse/exclude_scene)를 가리지 않으므로 그 형제의 {@code NOT_EXCLUDE_SCENE} 같은 처리결과별 체크 대신 「대기 후보가
     * 하나라도 있는가」를 마지막에 본다. 이 존재 확인이 끝난 뒤에야 {@link VerificationInputPort#load}를 호출하므로, 신고가 없을 때 그 어댑터의
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
        if (candidates.tagEvidenceIds().isEmpty() && candidates.rules().isEmpty()) {
            throw new BusinessException(VerificationErrorCode.NO_PENDING_CANDIDATES);
        }
        // 방어: 같은 규칙을 교체 대상으로 가리키는 대기 후보가 둘 이상이면 함께 확정할 수 없다 — 첫 교체 뒤
        // 둘째의 deactivate 가 0행이 돼 롤백된다. 생성 단계에서 같은 잠금으로 이미 막지만, 레거시·직접 주입
        // 데이터까지 검증 전에 거른다 (S15P21A501-309).
        var replacedTargets = new java.util.HashSet<Long>();
        for (PendingCandidates.RuleCandidate rule : candidates.rules()) {
            if (rule.replacedRuleId() != null && !replacedTargets.add(rule.replacedRuleId())) {
                throw new BusinessException(ParseRuleCandidateErrorCode.REPLACES_CONFLICT);
            }
        }

        VerificationInput input = inputPort.load(feedbackId);
        ExecuteSearchQuery query = new ExecuteSearchQuery(input.rawQuery(), input.dateFilters(), reviewerId);

        // pending_rules/pending_tags 축을 방금 로드한 candidates 에서 낸다(재조회 아님, S15P21A501-83 경합 수정) —
        // 그래야 recorded fingerprint 의 pending 축 == 아래에서 실제로 flip·검색할 집합이 항상 같다. candidates 로드와
        // 이 계산 사이에 새 후보가 커밋돼도 여기 잡히지 않으므로(의도), -84 재조회 compute(feedbackId) 는 그 새 후보를
        // 보고 값이 달라져 드리프트를 정확히 잡는다.
        List<Long> loadedPendingRuleIds = candidates.rules().stream()
                .map(PendingCandidates.RuleCandidate::approvedRuleId)
                .toList();
        String baselineFingerprint = fingerprint.compute(
                feedbackId, loadedPendingRuleIds, candidates.tagEvidenceIds()); // flip 전 기준 상태 = flip 할 집합
        long executionId = record.start(new StartSearchExecution(
                reviewerId, StartSearchExecution.ExecutionType.REPLAY, feedbackId, input.rawQuery(), null));
        long startedAt = System.nanoTime();
        try {
            // 리졸버 HTTP 는 flip 과 무관하다(S15P21A501-219) — 롤백 트랜잭션을 열기 전에 끝내 둔다.
            // 트랜잭션 안에서 부르면 리졸버 지연만큼 flip 이 쥔 행 잠금·커넥션 점유가 늘어난다.
            InterpretSearchQueryUseCase.Resolution resolution = interpreter.resolve(query);
            VerificationSearchOutcome outcome = searchWithCandidatesRolledBack(query, candidates, resolution);
            // 기준선은 원 신고 실행의 저장 결과가 아니라 같은 순간의 대조군(후보 미적용)이다 — 후보와 무관한
            // 드리프트를 배제한다 (S15P21A501-281).
            List<Long> controlSceneIds =
                    outcome.controlCandidates().scenes().stream().map(SearchCandidates.ScoredScene::sceneId).toList();
            SceneDiff.Result diff = SceneDiff.of(controlSceneIds, outcome.candidates());
            // complete() 는 해석 스냅샷(normalized_query 등)이 먼저 채워져 있어야 완결을 받아준다 — 일반 검색과
            // 같은 두 단계(recordResolution → complete)를 그대로 태운다. 롤백은 이미 끝났으므로 여기서 쓰는 값은
            // 롤백 전에 캡처해 둔 InterpretedQuery 그대로다.
            record.recordResolution(recordResolutionCommand(executionId, query, outcome.interpreted()));
            record.complete(completeCommand(executionId, outcome, candidates, baselineFingerprint, startedAt));
            // 진입(실험군에만)·이탈(대조군에만) 장면 상세를 FE 가 모두 조회할 수 있게 두 집합을 합쳐 담는다.
            var verificationScenes = new java.util.LinkedHashMap<Long, VerificationScene>();
            outcome.controlCandidates().scenes().forEach(scene -> verificationScenes.put(
                    scene.sceneId(), toVerificationScene(scene)));
            outcome.candidates().scenes().forEach(scene -> verificationScenes.put(
                    scene.sceneId(), toVerificationScene(scene)));
            return new VerificationResult(
                    executionId, diff.entered(), diff.dropped(), outcome.activeRuleSet(), verificationScenes);
        } catch (RuntimeException failed) {
            // running 누수 금지 (§8, -59 H5 패턴): 넓은 catch 로 실행 행을 fail 로 닫는다.
            record.fail(executionId, verificationErrorCode(failed), elapsedMs(startedAt));
            throw failed;
        }
    }

    /**
     * 일반 검색 {@code SearchAssemblyService.completeRecord} 와 같은 조립: 해석 단계 사유 + 후보 구간 사유를 합치고, 규칙 degraded 여부까지 반영해 status
     * 를 매핑한다. {@code verificationContext} 는 -84 {@code VerificationRunQueryAdapter} 의 엄격 계약(§10)을 채운다.
     */
    private CompleteSearchExecution completeCommand(
            long executionId,
            VerificationSearchOutcome outcome,
            PendingCandidates candidates,
            String baselineFingerprint,
            long startedAt) {
        SearchCandidates result = outcome.candidates();
        InterpretedQuery interpreted = outcome.interpreted();
        // 검증 카드는 지금까지 쓰던 토큰 목록을 그대로 쓴다 — 이 경로가 넘기는 값은 확장어뿐이므로 출처도 전부 expanded 다.
        // 원 질의 토큰이 이 경로의 matched_keywords 에 들어가지 않는 것은 S15P21A501-234 이전부터의 동작이며, 이 티켓에서 바꾸지 않는다.
        List<String> expandedTokens = result.expandedTokens();

        List<ParseRuleOutcome> appliedRules = interpreted.rules().outcomes();
        List<SearchDegradedReason> degradedReasons = new ArrayList<>(interpreted.degradedReasons());
        degradedReasons.addAll(result.degradedReasons());
        boolean ruleDegraded =
                appliedRules.stream().anyMatch(rule -> rule.status().degraded());
        CompleteSearchExecution.ExecutionStatus status = degradedReasons.isEmpty() && !ruleDegraded
                ? CompleteSearchExecution.ExecutionStatus.SUCCEEDED
                : CompleteSearchExecution.ExecutionStatus.DEGRADED;

        var context = new LinkedHashMap<String, Object>();
        context.put("resolution", candidates.resolution());
        context.put("approved_evidence_ids", candidates.tagEvidenceIds());
        // -84 확정 소비자는 단수 ID를 요구한다. 복수 후보 전체도 별도 필드에 기록한다.
        PendingCandidates.RuleCandidate firstRule =
                candidates.rules().isEmpty() ? null : candidates.rules().get(0);
        context.put("approved_rule_id", firstRule == null ? null : firstRule.approvedRuleId());
        context.put("replaced_rule_id", firstRule == null ? null : firstRule.replacedRuleId());
        context.put("approved_rule_action", firstRule == null ? null : firstRule.action());
        context.put(
                "candidate_rules",
                candidates.rules().stream()
                        .map(rule -> {
                            Map<String, Object> pair = new LinkedHashMap<>();
                            pair.put("approved_rule_id", rule.approvedRuleId());
                            pair.put("replaced_rule_id", rule.replacedRuleId());
                            pair.put("action", rule.action());
                            return pair;
                        })
                        .toList());
        context.put("state_fingerprint", baselineFingerprint);
        // baselineFingerprint 의 pending_rules/pending_tags 축을 그대로 낸 정확한 id 집합(S15P21A501-83) — 위
        // approved_evidence_ids/candidate_rules 와 값은 같지만, -84 가 "이 지문의 pending 축이 정확히 이 id들에서
        // 나왔다"를 필드명으로 바로 확인하도록 별도 키로 둔다. approved_evidence_ids 는 -84 VerificationRunQueryAdapter
        // 엄격 계약(양의 long 배열)을 이미 쓰므로 재사용하지 않고 새 키를 추가한다.
        context.put(
                "verified_candidate_ids",
                Map.of(
                        "rule_ids",
                                candidates.rules().stream()
                                        .map(PendingCandidates.RuleCandidate::approvedRuleId)
                                        .toList(),
                        "tag_evidence_ids", candidates.tagEvidenceIds()));
        context.put("candidate_tag_changes", candidates.tagEvidenceIds()); // §7.2 후보 태그 변경안
        context.put("baseline_state", Map.of("state_fingerprint", baselineFingerprint)); // §7.2 기준 상태
        context.put("verification_rule_set", outcome.activeRuleSet()); // §7.2 검증 규칙 집합 (활성 − R1 + R2)
        return new CompleteSearchExecution(
                executionId,
                status,
                degradedReasons,
                null,
                interpreted.finalResolution(), // 검증이 실제로 쓴 해석 — replay 행도 일반 검색과 같은 충실도
                appliedRules,
                SearchRecordPayload.candidates(result),
                SearchRecordPayload.filtered(result),
                SearchRecordPayload.appliedExcludes(result),
                SearchRecordPayload.rankedScenes(result, List.of(), expandedTokens),
                result.config(),
                elapsedMs(startedAt),
                context);
    }

    /**
     * {@code SearchAssemblyService.resolverOutput} 과 같은 조립. AI 가 실제로 무엇을 주장했는지(raw) 와 anchor 검증 후(verified) 를 함께 남긴다.
     */
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
     * {@link SearchAssemblyService#abandon} 과 같은 분류 — BusinessException 은 자기 코드를, 그 외는 기본값을 남긴다. 기본값은
     * {@code SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED} 가 아니라 {@link VerificationErrorCode#VERIFICATION_FAILED} 다
     * — 그쪽은 단어 검색 전용이라 랭커 내부 오류·기록 커밋 실패 같은 검증 전반의 실패에 붙이면 사유가 틀리게 남는다(Task 4 리뷰 지적).
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
     * 롤백 트랜잭션 안에서 검색 결과와 해석을 함께 캡처한다. 검색은 롤백되지만 이 값은 트랜잭션 밖으로 나가 replay 기록에 쓰인다 — 일반 검색 기록과 같은
     * 충실도(finalResolution·degradedReasons· rules().outcomes())를 남기기 위해 {@code SearchCandidates} 만이 아니라
     * {@code InterpretedQuery} 도 담는다.
     */
    record VerificationSearchOutcome(
            SearchCandidates candidates,
            SearchCandidates controlCandidates,
            InterpretedQuery interpreted,
            List<Long> activeRuleSet) {}

    /**
     * 같은 트랜잭션 안에서 대조군(A)·실험군(B)을 연속 검색해 <b>후보의 순효과만</b> 낸다 (FRD F-12.4
     * "새로 검색될 장면과 제외될 장면을 계산"). 예전에는 실험군을 원 신고 실행의 <i>저장된</i> 결과 행과
     * 비교했는데, 그 사이 코퍼스 증가·클립 재처리(새 scene_id)·타 피드백 교정·리졸버 드리프트가 전부 diff 에
     * 섞여 "태그 하나 삭제했는데 7개 진입·7개 이탈"처럼 후보와 무관한 변화가 나왔다 (S15P21A501-281).
     * 이제 대조군·실험군을 <b>같은 순간·같은 코퍼스·같은 해석</b>으로 돌리고 그 둘만 비교하므로, 두 검색에
     * 공통인 드리프트는 서로 상쇄되어 후보가 실제로 넣고 뺀 장면만 남는다.
     *
     * <p>{@code resolution} 은 트랜잭션 밖에서 이미 끝난 리졸버 호출 결과다(S15P21A501-219) — 여기서는
     * {@link InterpretSearchQueryUseCase#interpretFromResolution} 만 불러 활성 규칙을 읽는다. 대조군은 flip 전,
     * 실험군은 flip 후에 읽어 규칙 상태가 정확히 후보만큼 다르다.
     */
    private VerificationSearchOutcome searchWithCandidatesRolledBack(
            ExecuteSearchQuery query, PendingCandidates candidates, InterpretSearchQueryUseCase.Resolution resolution) {
        return rollbackTemplate.execute(status -> {
            // A) 대조군: 후보를 적용하지 않은 현재 상태의 검색.
            InterpretedQuery controlIq = interpreter.interpretFromResolution(query, resolution);
            SearchCandidates control = ranker.rank(rankQuery(controlIq));
            // B) 실험군: 후보를 임시 적용(flip)한 검색.
            candidateState.flip(candidates);
            InterpretedQuery treatmentIq = interpreter.interpretFromResolution(query, resolution);
            SearchCandidates treatment = ranker.rank(rankQuery(treatmentIq));
            List<Long> activeRuleSet = candidateState.readActivePatchRuleIds(); // flip 반영 상태 = 활성 − R1 + R2
            status.setRollbackOnly(); // flip 과 후보 적용을 모두 되돌린다
            return new VerificationSearchOutcome(treatment, control, treatmentIq, activeRuleSet);
        });
    }

    private RankSearchCandidatesUseCase.Query rankQuery(InterpretedQuery iq) {
        return new RankSearchCandidatesUseCase.Query(
                iq.resolved().normalization(), iq.finalResolution(),
                iq.resolved().queryEmbedding(), iq.normalizedSearch());
    }

    private VerificationScene toVerificationScene(SearchCandidates.ScoredScene scene) {
        return new VerificationScene(
                scene.sceneId(),
                scene.clipId(),
                scene.card().clipTitle(),
                scene.card().caption(),
                scene.card().startTimeMs(),
                scene.card().endTimeMs());
    }
}
