package com.npick.search.application.query.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchExecutionErrorCode;
import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.QueryResolverPort;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.SearchExecutionRecordPort.ExecutionStatus;
import com.npick.search.application.port.SearchExecutionRecordPort.ExecutionType;
import com.npick.search.application.port.SearchExecutionRecordPort.ParseSource;
import com.npick.search.application.port.SearchRecordingException;
import com.npick.search.application.port.StartSearchExecution;
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
 * <p>트랜잭션을 메서드에 걸지 않는다. 후보 조회 구간만 {@link RankSearchCandidatesUseCase} 가 자기 읽기 트랜잭션에서 돌고, 실행 기록은 그 밖에서 따로 커밋된다 (baseline
 * 주석 「실행 기록은 롤백 밖에 저장한다」).
 */
@Service
public class SearchAssemblyService implements ExecuteSearchUseCase {

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

        long resolveStartedAt = System.nanoTime();
        QueryResolutionResult resolved = anchorVerifier.verify(query.rawQuery(), resolver.resolve(query.rawQuery()));
        int parseMs = elapsedMs(resolveStartedAt);
        // 리졸버 장애가 아닌 실패는 여기서 예외로 올라간다. 해석 못 한 질의를 빈 결과의 성공
        // 응답으로 위장하지 않는다 (§6.2 「검색 실패를 결과 0건으로 위장하지 않는다」).
        List<SearchDegradedReason> degradedReasons = new ArrayList<>(SearchDegradedReason.reasonsFor(resolved));

        NormalizedSearch normalizedSearch = normalize(query, resolved);
        long executionId = openExecution(query, resolved, normalizedSearch, parseMs, degradedReasons);

        ParseRulePolicy.Result rules = applyRules(resolved);
        // fallback 에서도 명시 필터는 살아 있어야 한다. 사용자가 직접 건 날짜는 AI 해석과 무관한
        // 조건이고, F-06 의 「명시한 날짜와 검증된 날짜가 충돌하면 제외」가 그 값을 근거로 쓴다.
        // 해석이 없다고 필터까지 버리면 사용자가 건 조건을 조용히 무시하게 된다.
        QueryResolution finalResolution = explicitFilterPolicy.apply(
                rules.resolution() == null ? QueryResolution.withoutAiInterpretation() : rules.resolution(),
                query.explicitFilters());

        SearchCandidates candidates = runPipeline(resolved, finalResolution, normalizedSearch);
        degradedReasons.addAll(candidates.degradedReasons());

        boolean appliedRule =
                rules.outcomes().stream().anyMatch(outcome -> outcome.status() == ParseRuleOutcome.Status.APPLIED);
        // parse_source 와 applied_rules_json 을 같은 판정에서 만든다. 둘이 갈리면 기록을 읽는
        // 쪽이 규칙 적용 여부를 어느 값으로 보느냐에 따라 다른 답을 얻는다.
        ParseSource parseSource = !resolved.isResolved()
                ? ParseSource.FALLBACK
                : appliedRule ? ParseSource.RESOLVER_RULE : ParseSource.RESOLVER;

        // 경로별로 나눠 재지 않으면 95% 10초 목표가 어느 경로 때문에 깨지는지 알 수 없다 (§8.2).
        // 태그 값은 기록의 execution_type·parse_source 와 같은 판정에서 나온다.
        searchTimer.record(
                System.nanoTime() - startedAt,
                TimeUnit.NANOSECONDS,
                SearchPath.of(ExecutionType.ORIGINAL, parseSource));

        List<String> queryTokens = resolved.normalization().searchTokens();
        List<Long> resultIds = completeRecord(
                executionId,
                candidates,
                rules,
                finalResolution,
                parseSource,
                degradedReasons,
                queryTokens,
                elapsedMs(startedAt));

        return new SearchExecutionResult(
                resultIds == null ? null : executionId,
                degradedReasons.stream().map(SearchDegradedReason::jsonName).toList(),
                resolved.isResolved(),
                appliedRule,
                guardSummary(candidates),
                candidates.shortageReasons().stream()
                        .map(ShortageReason::wireValue)
                        .toList(),
                cards(candidates, resultIds, queryTokens));
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
            ParseSource parseSource,
            List<SearchDegradedReason> degradedReasons,
            List<String> queryTokens,
            int executionMs) {
        try {
            List<Long> resultIds = record.complete(new CompleteSearchExecution(
                    executionId,
                    degradedReasons.isEmpty() ? ExecutionStatus.SUCCEEDED : ExecutionStatus.DEGRADED,
                    degradedReasons,
                    null,
                    parseSource,
                    finalResolution,
                    rules.outcomes(),
                    candidates.candidates(),
                    new CompleteSearchExecution.FilterRecord(
                            candidates.scenes().size(), candidates.shortageReasons(), candidates.guard()),
                    candidates.appliedExcludes(),
                    rankedScenes(candidates, queryTokens),
                    candidates.config(),
                    executionMs,
                    null));
            return resultIds;
        } catch (SearchRecordingException notSaved) {
            degradedReasons.add(SearchDegradedReason.SNAPSHOT_SAVE_FAILED);
            return null;
        }
    }

    private List<CompleteSearchExecution.RankedScene> rankedScenes(
            SearchCandidates candidates, List<String> queryTokens) {
        List<CompleteSearchExecution.RankedScene> ranked = new ArrayList<>();
        int rank = 1;
        for (SearchCandidates.ScoredScene scene : candidates.scenes()) {
            ranked.add(new CompleteSearchExecution.RankedScene(scene.sceneId(), rank++, explain(scene, queryTokens)));
        }
        return ranked;
    }

    /**
     * {@code search_result.explain_json}. baseline COLUMN COMMENT 가 정한 {@code score}·{@code match}·{@code guard} 에 당시
     * 표시값 {@code display} 를 더한 네 덩어리다.
     */
    private Map<String, Object> explain(SearchCandidates.ScoredScene scene, List<String> queryTokens) {
        var explain = new LinkedHashMap<String, Object>();
        explain.put("score", SearchExplain.score(scene));
        explain.put("match", SearchExplain.match(scene, queryTokens));
        explain.put("guard", SearchExplain.guard(scene));
        explain.put("display", SearchExplain.display(scene));
        return explain;
    }

    private List<SearchExecutionResult.ResultCard> cards(
            SearchCandidates candidates, List<Long> resultIds, List<String> queryTokens) {
        List<SearchExecutionResult.ResultCard> cards = new ArrayList<>();
        for (int index = 0; index < candidates.scenes().size(); index++) {
            SearchCandidates.ScoredScene scene = candidates.scenes().get(index);
            Long resultId = resultIds == null ? null : resultIds.get(index);
            cards.add(SearchExplain.card(scene, index + 1, resultId, queryTokens));
        }
        return cards;
    }

    /** 제외 건만 센다. 통과한 장면의 판정까지 실으면 화면이 존재하지 않는 충돌을 띄운다 (§5.1). */
    private SearchExecutionResult.GuardSummary guardSummary(SearchCandidates candidates) {
        List<String> reasons = candidates.guard().excluded().stream()
                .map(verdict -> verdict.exclusionReason().wireValue())
                .distinct()
                .toList();
        return new SearchExecutionResult.GuardSummary(
                candidates.guard().excluded().size(), reasons);
    }


    /**
     * 실행을 연다. 실패하면 검색을 중단한다 (§6.2 「검색 실행의 최초 저장 실패」).
     *
     * <p>여기서 계속 진행하면 결과를 돌려주고도 그 실행이 어디에도 없다. 신고·검증이 가리킬 대상이 없어 사용자가 이상하다고 말할 방법이 사라진다.
     */
    private long openExecution(
            ExecuteSearchQuery query,
            QueryResolutionResult resolved,
            NormalizedSearch normalizedSearch,
            int parseMs,
            List<SearchDegradedReason> degradedReasons) {
        try {
            return record.start(new StartSearchExecution(
                    query.memberId(),
                    ExecutionType.ORIGINAL,
                    null,
                    query.rawQuery(),
                    query.explicitFilters(),
                    normalizedSearch,
                    resolverOutput(resolved),
                    resolved.findings(),
                    resolved.isResolved() ? ParseSource.RESOLVER : ParseSource.FALLBACK,
                    parseMs,
                    degradedReasons));
        } catch (SearchRecordingException notOpened) {
            throw new BusinessException(SearchExecutionErrorCode.EXECUTION_NOT_RECORDED, notOpened);
        }
    }

    /**
     * 후보 구간을 돌린다.
     *
     * <p>{@link BusinessException} 은 그대로 올린다 — 채널·후보 계층이 이미 자기 사유로 분류한 실패다. 그 밖의 예외만 「기본 단어 검색도 불가」로 본다 (§6.2).
     * 여기서 삼키면 검색 실패가 0건의 성공 응답으로 나간다.
     */
    private SearchCandidates runPipeline(
            QueryResolutionResult resolved, QueryResolution finalResolution, NormalizedSearch normalizedSearch) {
        try {
            return pipeline.rank(new RankSearchCandidatesUseCase.Query(
                    resolved.normalization(), finalResolution, resolved.queryEmbedding(), normalizedSearch));
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
            // 해석이 없으면 규칙의 조건을 판정할 대상이 없다. 조회 자체를 건너뛴다 — 규칙 조회
            // 실패를 검색 실패로 다루는 §6.2 와 어긋나지 않는다. 여기서는 물어볼 것이 없다.
            return new ParseRulePolicy.Result(null, List.of());
        }
        try {
            return parseRulePolicy.apply(resolved.resolution(), parseRules.findActivePatchParseRules());
        } catch (BusinessException alreadyClassified) {
            throw alreadyClassified;
        } catch (RuntimeException failed) {
            throw new BusinessException(SearchExecutionErrorCode.ACTIVE_RULE_LOOKUP_FAILED, failed);
        }
    }

    private StartSearchExecution.ResolverOutput resolverOutput(QueryResolutionResult resolved) {
        if (!resolved.isResolved()) {
            return null;
        }
        return new StartSearchExecution.ResolverOutput(
                resolved.resolution(),
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
