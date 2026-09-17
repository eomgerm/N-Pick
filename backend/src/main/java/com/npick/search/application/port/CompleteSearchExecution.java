package com.npick.search.application.port;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.npick.search.application.port.SearchExecutionRecordPort.ExecutionStatus;
import com.npick.search.application.port.SearchExecutionRecordPort.ParseSource;
import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult;
import com.npick.search.application.query.fusion.FuseSearchRankingQuery;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.ShortageReason;

/**
 * 실행을 닫는 값 — 결과·근거·실제 사용 설정 전부.
 *
 * @param parseSource {@code start} 값을 덮어쓴다. 규칙이 하나라도 적용됐으면 {@link ParseSource#RESOLVER_RULE} 로 올라오고, 그 판정은
 *     {@link #appliedRules} 에 {@code applied} 가 있는지와 항상 일치한다
 * @param finalResolution 규칙·명시 필터까지 적용한 실제 사용 해석 ({@code parsed_query_json})
 * @param candidates 거르기 전 후보. 순위 결합에 넘긴 것과 <b>같은 세 결과</b>라 별도 타입을 만들지 않는다 — 기록과 계산이 갈리면 「그때 무엇을 보고 이 순위가 나왔나」에
 *     답할 수 없다
 * @param filtered 무엇이 왜 빠졌는가. {@code filtered_json} 은 「내가 아는 그 영상이 왜 안 나왔는지에 답하는 유일한 기록」이다 (baseline 주석)
 * @param rankedScenes 살아남은 장면만, rank 오름차순. 순서가 그대로 {@code search_result.result_rank} 다
 * @param verificationContext 검증 실행에만. 일반 검색은 반드시 {@code null} 이다 (§7.2, ck 제약)
 */
public record CompleteSearchExecution(
        long searchExecutionId,
        ExecutionStatus status,
        List<SearchDegradedReason> degradedReasons,
        String errorCode,
        ParseSource parseSource,
        QueryResolution finalResolution,
        List<ParseRuleOutcome> appliedRules,
        FuseSearchRankingQuery candidates,
        FilterRecord filtered,
        List<ActiveSceneExclusionResult.ExcludedScene> appliedExcludes,
        List<RankedScene> rankedScenes,
        SearchConfigSnapshot config,
        int executionMs,
        Map<String, Object> verificationContext) {

    public CompleteSearchExecution {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(parseSource, "parseSource");
        Objects.requireNonNull(config, "config");
        if (status == ExecutionStatus.RUNNING) {
            throw new IllegalArgumentException("complete 는 실행을 닫는다 — RUNNING 으로 닫을 수 없다");
        }
        degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
        appliedRules = appliedRules == null ? List.of() : List.copyOf(appliedRules);
        appliedExcludes = appliedExcludes == null ? List.of() : List.copyOf(appliedExcludes);
        rankedScenes = List.copyOf(rankedScenes);
    }

    /**
     * 뺀 것들과 뺀 이유, 그리고 실제 반환 수.
     *
     * @param returnedCount 실제로 내보낸 결과 수. 10 미만이면 {@link #shortageReasons} 가 비어 있지 않다 (F-06 완료 기준)
     * @param guard 통과한 장면의 판정도 함께 들어 있다. 제외 건만 남기면 「왜 이건 살아남았나」에 답할 수 없다
     */
    public record FilterRecord(int returnedCount, List<ShortageReason> shortageReasons, FalseHitGuardResult guard) {
        public FilterRecord {
            shortageReasons = shortageReasons == null ? List.of() : List.copyOf(shortageReasons);
        }
    }

    /**
     * 결과 한 줄.
     *
     * @param explain {@code search_result.explain_json}. baseline 이 정한 {@code score}·{@code match}·{@code guard} 에 당시
     *     표시값 {@code display} 를 더한 네 덩어리다. -60 은 가공하지 않고 그대로 저장한다
     */
    public record RankedScene(long sceneId, int rank, Map<String, Object> explain) {
        public RankedScene {
            explain = Map.copyOf(explain);
        }
    }
}
