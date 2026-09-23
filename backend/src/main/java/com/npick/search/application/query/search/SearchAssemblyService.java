package com.npick.search.application.query.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchExecutionErrorCode;
import com.npick.search.application.error.SearchRuleErrorCode;
import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.QueryResolverPort;
import com.npick.search.application.port.RecordSearchExecutionResolution;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.SearchRecordingException;
import com.npick.search.application.port.StartSearchExecution;
import com.npick.search.application.port.StartSearchExecution.ExecutionType;
import com.npick.search.application.port.StartSearchExecution.ParseSource;
import com.npick.search.application.resolution.AnchorVerifier;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.ShortageReason;
import com.npick.search.domain.policy.ExplicitFilterPolicy;
import com.npick.search.domain.policy.ParseRulePolicy;
import com.npick.search.domain.repository.ParseRuleRepository;

/**
 * 검색 한 번의 순서와 트랜잭션 경계를 소유한다.
 *
 * <p>기록은 세 단계다 (S15P21A501-60 의 포트). 실행을 <b>리졸버 호출 전에</b> 열고({@code start}), 해석과 규칙 판정이 끝나면 채우고
 * ({@code recordResolution}), 결과가 나오면 닫는다({@code complete}). §6.2 의 「검색 실행의 최초 저장 실패 → AI 호출 전에 중단」이 이 순서를 요구한다.
 *
 * <p>트랜잭션을 메서드에 걸지 않는다. 후보 조회 구간만 {@link RankSearchCandidatesUseCase} 가 자기 읽기 트랜잭션에서 돌고, 기록 세 호출은 그 밖에서 각각 커밋된다
 * (baseline 주석 「실행 기록은 롤백 밖에 저장한다」).
 */
@Service
public class SearchAssemblyService implements ExecuteSearchUseCase, InterpretSearchQueryUseCase {

    /** 계약 §5.1 의 허용 사유. guard 판정이 내는 값이 아니라 승인된 장면 제외(-58)가 내는 값이라 여기 둔다. */
    private static final String APPROVED_SCENE_EXCLUSION = "approved_scene_exclusion";

    private final QueryResolverPort resolver;
    private final ParseRuleRepository parseRules;
    private final RankSearchCandidatesUseCase pipeline;
    private final SearchExecutionRecordPort record;
    private final SearchDurationTimer searchTimer;

    // 상태 없는 정책은 주입하지 않고 만든다 (SearchRankingFusionService 의 RrfFusionPolicy 와 같은 규약).
    private final AnchorVerifier anchorVerifier = new AnchorVerifier();
    private final ParseRulePolicy parseRulePolicy = new ParseRulePolicy();
    private final ExplicitFilterPolicy explicitFilterPolicy = new ExplicitFilterPolicy();

    public SearchAssemblyService(
            QueryResolverPort resolver,
            ParseRuleRepository parseRules,
            RankSearchCandidatesUseCase pipeline,
            SearchExecutionRecordPort record,
            SearchDurationTimer searchTimer) {
        this.resolver = resolver;
        this.parseRules = parseRules;
        this.pipeline = pipeline;
        this.record = record;
        this.searchTimer = searchTimer;
    }

    @Override
    public SearchExecutionResult execute(ExecuteSearchQuery query) {
        long startedAt = System.nanoTime();
        long executionId = openExecution(query);
        try {
            return run(query, executionId, startedAt);
        } catch (RuntimeException failed) {
            // 열어 둔 실행을 닫는다. 안 닫으면 running 행이 영구히 남아 성공 기록과 구분되지 않는다
            // (§6.2 「성공 기록이 남았다고 주장하지 않는다」).
            //
            // BusinessException 만 잡으면 분류되지 않은 결함(불변식 위반 등)이 행을 열어 둔 채
            // 빠져나간다. 사유를 모르는 실패일수록 기록이 더 필요하다.
            abandon(executionId, failed, elapsedMs(startedAt));
            throw failed;
        }
    }

    /**
     * 검색어 해석까지만 한다. <b>기록을 하나도 부르지 않는다</b> — 후보 검증 재검색(-83)이 롤백 트랜잭션 안에서 같은 해석을 태우기 위한 자리다.
     *
     * <p>{@code execute()} 도 이 메서드를 쓴다. 두 경로가 다른 코드를 타면 FRD §11 의 「일반 검색과 같은 코드로 검증 검색을 한다」가 깨진다.
     */
    @Override
    public InterpretedQuery interpret(ExecuteSearchQuery query) {
        return interpretFromResolution(query, resolve(query));
    }

    /**
     * 리졸버 호출까지만 한다 — {@link #interpret} 의 유일한 외부 HTTP 구간이자 flip 상태와 무관한 부분(S15P21A501-219).
     *
     * <p>검증 재검색은 이 메서드를 롤백 트랜잭션 <b>밖</b>에서 부른다. 리졸버 지연이 flip 이 쥔 행 잠금·커넥션 점유 시간에 더해지지 않게 하기 위해서다.
     */
    @Override
    public Resolution resolve(ExecuteSearchQuery query) {
        long resolveStartedAt = System.nanoTime();
        // 검증 전 값을 따로 들고 있는다. §7.2 가 resolver_output_json 을 「교정 전 AI 해석」으로
        // 못박았는데, 검증본만 남기면 리졸버가 실제로 무엇을 주장했는지 사라진다 — 출처 강등과
        // 날짜 필드 교정이 무엇을 바꿨는지 되짚을 수 없다.
        QueryResolutionResult raw = resolver.resolve(query.rawQuery());
        QueryResolutionResult resolved = anchorVerifier.verify(query.rawQuery(), raw);
        int parseMs = elapsedMs(resolveStartedAt);
        return new Resolution(raw, resolved, parseMs);
    }

    /**
     * {@link #resolve} 이후 나머지 해석을 한다 — 활성 규칙 조회가 flip 반영 상태를 읽으므로(§규칙 판정) 검증 재검색은 이 메서드를 롤백 트랜잭션 <b>안</b>에서
     * 부른다(S15P21A501-219).
     */
    @Override
    public InterpretedQuery interpretFromResolution(ExecuteSearchQuery query, Resolution resolution) {
        QueryResolutionResult raw = resolution.raw();
        QueryResolutionResult resolved = resolution.resolved();
        int parseMs = resolution.parseMs();
        // 리졸버 장애가 아닌 실패는 여기서 예외로 올라간다. 해석 못 한 질의를 빈 결과의 성공
        // 응답으로 위장하지 않는다 (§6.2 「검색 실패를 결과 0건으로 위장하지 않는다」).
        List<SearchDegradedReason> degradedReasons = new ArrayList<>(SearchDegradedReason.reasonsFor(resolved));

        ParseRulePolicy.Result rules = applyRules(resolved);
        boolean appliedRule =
                rules.outcomes().stream().anyMatch(outcome -> outcome.status() == ParseRuleOutcome.Status.APPLIED);
        // ParseSource 에 resolver_rule 값이 없어 규칙 적용 여부는 여기 담기지 않는다. 기록에서
        // 그 사실을 읽으려면 applied_rules_json 의 applied 항목을 봐야 한다 (S15P21A501-60 에 보고).
        ParseSource parseSource = resolved.isResolved() ? ParseSource.RESOLVER : ParseSource.FALLBACK;

        // fallback 에서도 명시 필터는 살아 있어야 한다. 사용자가 직접 건 날짜는 AI 해석과 무관한
        // 조건이고, F-06 의 「명시한 날짜와 검증된 날짜가 충돌하면 제외」가 그 값을 근거로 쓴다.
        QueryResolution finalResolution = explicitFilterPolicy.apply(
                rules.resolution() == null ? QueryResolution.withoutAiInterpretation() : rules.resolution(),
                query.explicitFilters());

        return new InterpretedQuery(
                raw,
                resolved,
                parseMs,
                rules,
                appliedRule,
                parseSource,
                finalResolution,
                normalize(query, resolved),
                degradedReasons);
    }

    private SearchExecutionResult run(ExecuteSearchQuery query, long executionId, long startedAt) {
        InterpretedQuery interpreted = interpret(query);
        QueryResolutionResult resolved = interpreted.resolved();
        ParseRulePolicy.Result rules = interpreted.rules();
        boolean appliedRule = interpreted.appliedRule();
        ParseSource parseSource = interpreted.parseSource();
        QueryResolution finalResolution = interpreted.finalResolution();
        NormalizedSearch normalizedSearch = interpreted.normalizedSearch();
        List<SearchDegradedReason> degradedReasons = new ArrayList<>(interpreted.degradedReasons());

        boolean snapshotRecorded = recordResolution(
                executionId,
                query,
                interpreted.raw(),
                resolved,
                normalizedSearch,
                parseSource,
                interpreted.parseMs(),
                degradedReasons);

        SearchCandidates candidates = runPipeline(resolved, finalResolution, normalizedSearch, query.page());
        degradedReasons.addAll(candidates.degradedReasons());

        // 두 출처를 합치지 않고 끝까지 따로 들고 간다. 확장어도 근거 대조에는 넣되 (빼면 확장어로만 걸린
        // 장면의 matched_keywords 가 비어 「왜 나왔는지 모르는 결과」가 된다), 어느 것이 확장어였는지는
        // matched_keywords 의 origin 으로 구분해 싣는다 (web-api §5.1, F-05·F-07).
        List<String> userTokens = resolved.normalization().searchTokens();
        List<String> expandedTokens = candidates.expandedTokens();
        List<Long> resultIds = snapshotRecorded
                ? completeRecord(
                        executionId,
                        candidates,
                        rules,
                        finalResolution,
                        degradedReasons,
                        userTokens,
                        expandedTokens,
                        startedAt)
                // 해석 스냅샷이 없으면 어댑터가 완료를 거부한다. 그래도 complete 를 부르면 두 번째
                // 실패가 또 삼켜지고 행이 영영 running 으로 남는다. 바로 닫는다.
                : abandonUnrecorded(executionId, startedAt);

        // 기록 저장까지 포함해 찍는다. 앞에서 끊으면 같은 실행의 execution_ms 와 값이 갈리고,
        // 저장이 느릴 때 그 시간이 어느 지표에도 안 잡힌다 (§8.2).
        searchTimer.record(
                System.nanoTime() - startedAt,
                TimeUnit.NANOSECONDS,
                SearchPath.of(ExecutionType.NORMAL, parseSource, appliedRule));

        return new SearchExecutionResult(
                resultIds == null ? null : executionId,
                degradedReasons.stream().map(SearchDegradedReason::jsonName).toList(),
                resolved.isResolved(),
                appliedRule,
                guardSummary(candidates),
                candidates.shortageReasons().stream()
                        .map(ShortageReason::wireValue)
                        .toList(),
                cards(candidates, resultIds, userTokens, expandedTokens),
                candidates.hasNext());
    }

    /**
     * 실행을 연다. 리졸버 호출 <b>전</b>이고, 실패하면 검색을 중단한다 (§6.2 「검색 실행의 최초 저장 실패」).
     *
     * <p>여기서 계속 진행하면 결과를 돌려주고도 그 실행이 어디에도 없다. 신고·검증이 가리킬 대상이 없어 사용자가 이상하다고 말할 방법이 사라진다.
     */
    private long openExecution(ExecuteSearchQuery query) {
        try {
            return record.start(new StartSearchExecution(
                    query.memberId(), ExecutionType.NORMAL, null, query.rawQuery(), query.parentExecutionId()));
        } catch (SearchRecordingException notOpened) {
            throw new BusinessException(SearchExecutionErrorCode.EXECUTION_NOT_RECORDED, notOpened);
        }
    }

    /**
     * 해석과 규칙 판정 결과를 실행에 채운다.
     *
     * <p>규칙 적용 <b>뒤</b>에 부른다. baseline 이 정의한 {@code parse_source=resolver_rule} 을 언젠가 기록하려면 규칙 판정이 끝나 있어야 하고, 리졸버 직후에
     * 부르면 그 값을 넣을 자리가 영영 없어진다. 현재 enum 에 그 값이 없어 지금은 {@code resolver} 로 나간다.
     *
     * <p>저장 실패는 검색을 죽이지 않는다. 해석은 이미 끝났고 결과를 낼 수 있다 — 대신 사유를 남겨 응답이 미저장 상태임을 알린다 (§6.2 「결과 계산 후 기록 저장 실패」와 같은 취급이다. 둘을
     * 갈라 다루면 같은 「기록이 불완전하다」가 두 얼굴을 갖는다).
     */
    private boolean recordResolution(
            long executionId,
            ExecuteSearchQuery query,
            QueryResolutionResult raw,
            QueryResolutionResult resolved,
            NormalizedSearch normalizedSearch,
            ParseSource parseSource,
            int parseMs,
            List<SearchDegradedReason> degradedReasons) {
        try {
            record.recordResolution(new RecordSearchExecutionResolution(
                    executionId,
                    query.explicitFilters(),
                    normalizedSearch,
                    resolverOutput(raw, resolved),
                    resolved.findings(),
                    parseSource,
                    parseMs,
                    degradedReasons));
            return true;
        } catch (SearchRecordingException notRecorded) {
            degradedReasons.add(SearchDegradedReason.SNAPSHOT_SAVE_FAILED);
            return false;
        }
    }

    /**
     * 실패로 끝난 실행을 닫는다.
     *
     * <p>여기서 난 예외는 삼킨다. 사용자에게 돌아가야 하는 것은 검색이 왜 실패했는가이지, 그 실패를 기록하다 또 실패했다는 사실이 아니다. 삼키지 않으면 원래 사유가
     * {@code SearchRecordingException} 에 가려진다.
     */
    private void abandon(long executionId, RuntimeException failed, int executionMs) {
        try {
            String code = failed instanceof BusinessException business
                    ? business.errorCode().code()
                    : SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED.code();
            record.fail(executionId, code, executionMs);
        } catch (RuntimeException notRecorded) {
            // 실행은 running 으로 남는다. 그 행을 성공으로 읽지 않는 것은 기록을 읽는 쪽의 규약이다.
        }
    }

    /**
     * 후보 구간을 돌린다.
     *
     * <p>{@link BusinessException} 은 그대로 올린다 — 채널·후보 계층이 이미 자기 사유로 분류한 실패다. 그 밖의 예외만 「기본 단어 검색도 불가」로 본다 (§6.2). 여기서 삼키면
     * 검색 실패가 0건의 성공 응답으로 나간다.
     */
    private SearchCandidates runPipeline(
            QueryResolutionResult resolved,
            QueryResolution finalResolution,
            NormalizedSearch normalizedSearch,
            int page) {
        try {
            return pipeline.rank(new RankSearchCandidatesUseCase.Query(
                    resolved.normalization(), finalResolution, resolved.queryEmbedding(), normalizedSearch, page));
        } catch (BusinessException alreadyClassified) {
            throw alreadyClassified;
        } catch (RuntimeException failed) {
            throw new BusinessException(SearchExecutionErrorCode.LEXICAL_SEARCH_FAILED, failed);
        }
    }

    /**
     * 승인된 해석 규칙을 읽는다. 실패는 검색 실패다 (§6.2 「활성 규칙 조회 실패」).
     *
     * <p>빈 목록으로 넘기면 검수자가 승인한 교정이 빠진 결과가 정상인 것처럼 나간다 — 사람의 결정을 조용히 건너뛰는 경로다.
     */
    private ParseRulePolicy.Result applyRules(QueryResolutionResult resolved) {
        if (!resolved.isResolved()) {
            // 해석이 없으면 규칙의 조건을 판정할 대상이 없다. 조회 자체를 건너뛴다 — 물어볼 것이 없다.
            return new ParseRulePolicy.Result(null, List.of());
        }
        try {
            return parseRulePolicy.apply(resolved.resolution(), parseRules.findActivePatchParseRules());
        } catch (BusinessException failed) {
            // 어댑터는 해석 규칙과 장면 제외 규칙에 같은 SRCH_503_201 을 쓴다. 그대로 올리면 계약이
            // 장면 제외 전용으로 적어 둔 코드가 해석 규칙 실패에도 나가 FE 안내가 엉뚱해진다.
            // 여기서는 무엇을 조회했는지 알고 있으므로 더 좁은 코드로 좁힌다.
            if (failed.errorCode() == SearchRuleErrorCode.RULE_LOOKUP_FAILED) {
                throw new BusinessException(SearchExecutionErrorCode.ACTIVE_RULE_LOOKUP_FAILED, failed);
            }
            throw failed;
        } catch (RuntimeException failed) {
            throw new BusinessException(SearchExecutionErrorCode.ACTIVE_RULE_LOOKUP_FAILED, failed);
        }
    }

    /**
     * 실행 기록을 닫고 결과 ID 를 받는다.
     *
     * @return 저장 실패면 {@code null}. 계산된 결과는 그대로 주되 저장된 ID 를 만들었다고 표시하지 않는다 (§6.2)
     */
    private List<Long> completeRecord(
            long executionId,
            SearchCandidates candidates,
            ParseRulePolicy.Result rules,
            QueryResolution finalResolution,
            List<SearchDegradedReason> degradedReasons,
            List<String> userTokens,
            List<String> expandedTokens,
            long startedAt) {
        try {
            // 규칙이 충돌·비호환·실패로 건너뛰어졌으면 그것도 기능 저하다. 공개 어휘(§5.1)에는
            // 그 사유가 없지만 CompleteSearchExecution 은 applied_rules 로 같은 판정을 하므로,
            // SearchDegradedReason 만 보고 SUCCEEDED 로 닫으면 불변식 위반으로 던진다.
            boolean ruleDegraded = rules.outcomes().stream()
                    .anyMatch(outcome -> outcome.status().degraded());
            return record.complete(new CompleteSearchExecution(
                    executionId,
                    degradedReasons.isEmpty() && !ruleDegraded
                            ? CompleteSearchExecution.ExecutionStatus.SUCCEEDED
                            : CompleteSearchExecution.ExecutionStatus.DEGRADED,
                    degradedReasons,
                    null,
                    finalResolution,
                    rules.outcomes(),
                    SearchRecordPayload.candidates(candidates),
                    SearchRecordPayload.filtered(candidates),
                    SearchRecordPayload.appliedExcludes(candidates),
                    SearchRecordPayload.rankedScenes(candidates, userTokens, expandedTokens),
                    candidates.config(),
                    elapsedMs(startedAt),
                    null));
        } catch (SearchRecordingException notSaved) {
            degradedReasons.add(SearchDegradedReason.SNAPSHOT_SAVE_FAILED);
            return null;
        }
    }

    /**
     * 해석 스냅샷을 남기지 못한 실행을 닫는다.
     *
     * <p>결과는 이미 계산됐으므로 사용자에게 준다 (§6.2 「계산된 결과는 미저장 상태로 제공」). 다만 그 실행의 <b>기록</b>은 실패했고, 어댑터가 불완전한 스냅샷의 완료를 거부하므로
     * {@code failed} 로 닫는 것이 이 행에 줄 수 있는 유일한 종료 상태다.
     *
     * @return 항상 {@code null} — 저장된 결과 ID 가 없다
     */
    private List<Long> abandonUnrecorded(long executionId, long startedAt) {
        try {
            record.fail(executionId, SearchExecutionErrorCode.EXECUTION_NOT_RECORDED.code(), elapsedMs(startedAt));
        } catch (RuntimeException notRecorded) {
            // 닫지도 못했다. running 으로 남고, 그 행을 성공으로 읽지 않는 것은 읽는 쪽의 규약이다.
        }
        return null;
    }

    private List<SearchExecutionResult.ResultCard> cards(
            SearchCandidates candidates, List<Long> resultIds, List<String> userTokens, List<String> expandedTokens) {
        List<SearchExecutionResult.ResultCard> cards = new ArrayList<>();
        for (int index = 0; index < candidates.scenes().size(); index++) {
            SearchCandidates.ScoredScene scene = candidates.scenes().get(index);
            Long resultId = resultIds == null ? null : resultIds.get(index);
            cards.add(SearchExplain.card(scene, index + 1, resultId, userTokens, expandedTokens));
        }
        return cards;
    }

    /**
     * 걷어낸 건수와 사유. 통과한 장면의 판정까지 실으면 화면이 존재하지 않는 충돌을 띄운다 (§5.1).
     *
     * <p><b>승인된 장면 제외도 여기 센다.</b> 계약이 {@code approved_scene_exclusion} 을 허용 사유로 들어 두었고, 사용자에게는 「내가 아는 그 장면이 왜 안 나왔나」가
     * guard 판정이든 검수자 승인이든 같은 질문이다. 하나만 세면 그 수가 실제로 빠진 것보다 적게 나간다.
     */
    private SearchExecutionResult.GuardSummary guardSummary(SearchCandidates candidates) {
        List<String> reasons = new ArrayList<>(candidates.guard().excluded().stream()
                .map(verdict -> verdict.exclusionReason().wireValue())
                .distinct()
                .toList());
        // 장면 기준으로 센다. 「제외된 결과 수」이지 「제외 판정 수」가 아니다. 현재 순서에서는
        // guard 를 통과한 장면만 장면 제외로 넘어가 겹칠 수 없지만, 단순 합산으로 두면 순서가
        // 바뀌는 순간 같은 장면이 두 번 세어진다.
        var excludedScenes = new java.util.LinkedHashSet<Long>();
        candidates.guard().excluded().forEach(verdict -> excludedScenes.add(verdict.sceneId()));
        if (!candidates.appliedExcludes().isEmpty()) {
            reasons.add(APPROVED_SCENE_EXCLUSION);
            candidates.appliedExcludes().forEach(excluded -> excludedScenes.add(excluded.sceneId()));
        }
        return new SearchExecutionResult.GuardSummary(excludedScenes.size(), reasons);
    }

    private RecordSearchExecutionResolution.ResolverOutput resolverOutput(
            QueryResolutionResult raw, QueryResolutionResult resolved) {
        if (!resolved.isResolved()) {
            return null;
        }
        return new RecordSearchExecutionResolution.ResolverOutput(
                raw.resolution(),
                resolved.resolution(),
                resolved.resolutionSchemaVersion(),
                resolved.promptVersion(),
                resolved.modelVersion());
    }

    /**
     * 같은 검색을 구분하는 지문. <b>명시 필터가 반드시 들어간다</b> — F-05 가 「정규화한 검색어·명시 필터·정규화 버전」을 지문의 재료로 못박았다. 필터를 빼면 날짜만 다른 두 검색이 같은 지문이
     * 되어, 한쪽에 승인된 장면 제외가 다른 쪽에도 걸린다.
     */
    private NormalizedSearch normalize(ExecuteSearchQuery query, QueryResolutionResult resolved) {
        var filters = new LinkedHashMap<String, List<String>>();
        query.explicitFilters()
                .ranges()
                .forEach((field, range) -> filters.put(
                        field.name().toLowerCase(Locale.ROOT),
                        List.of(range.from().toString(), range.to().toString())));
        return NormalizedSearch.of(
                resolved.normalization().normalizedQuery(),
                filters,
                resolved.normalization().normalizationVersion());
    }

    private int elapsedMs(long startedAtNanos) {
        return (int) ((System.nanoTime() - startedAtNanos) / 1_000_000L);
    }
}
