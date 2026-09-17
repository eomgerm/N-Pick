package com.npick.search.application.port;

import java.util.List;
import java.util.Objects;

import com.npick.search.application.port.SearchExecutionRecordPort.ExecutionType;
import com.npick.search.application.port.SearchExecutionRecordPort.ParseSource;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.QueryResolution;

/**
 * 실행을 여는 값 — 해석까지 끝났고 검색은 아직 돌지 않은 시점의 전부.
 *
 * <p>여기 없는 컬럼은 {@link CompleteSearchExecution} 이 채운다. {@code search_config_json}·{@code config_version} 이 그렇다 —
 * 실제 사용 설정은 dense·구조화 조회 결과에 딸려 와야 해서 이 시점에 만들 수 없다.
 *
 * @param resolverOutput 해석 실패(fallback)면 {@code null}. 그때도 정규화는 살아 있어 BM25 는 돈다
 * @param findings {@code AnchorVerifier} 가 무엇을 고쳤는가. 사람이 읽는 기록이고 판정 입력이 아니다 — 강등 결과 자체는
 *     {@link ResolverOutput#verifiedResolution()} 안 anchor 의 {@code origin} 에 있다
 * @param parseSource 이 시점에는 {@link ParseSource#RESOLVER} 또는 {@link ParseSource#FALLBACK} 뿐이다. 규칙 적용은 아직
 *     판정되지 않았으므로 {@link ParseSource#RESOLVER_RULE} 은 {@code complete} 에서 올라온다
 * @param degradedReasons 해석 단계까지 확정된 사유만
 */
public record StartSearchExecution(
        long searchedById,
        ExecutionType executionType,
        Long replayOfFeedbackId,
        String rawQuery,
        ExplicitDateFilters explicitFilters,
        NormalizedSearch normalizedSearch,
        ResolverOutput resolverOutput,
        List<AnchorFinding> findings,
        ParseSource parseSource,
        Integer parseMs,
        List<SearchDegradedReason> degradedReasons) {

    public StartSearchExecution {
        Objects.requireNonNull(executionType, "executionType");
        Objects.requireNonNull(rawQuery, "rawQuery");
        Objects.requireNonNull(explicitFilters, "explicitFilters");
        Objects.requireNonNull(normalizedSearch, "normalizedSearch");
        Objects.requireNonNull(parseSource, "parseSource");
        // ck_execution_replay_pair 를 DB 에 닿기 전에 막는다. 어긋난 채로 내려가면 제약 위반이
        // 저장 실패로 올라와 §6.2 의 "최초 저장 실패" 로 잘못 분류된다 — 그건 DB 장애가 아니라
        // 조립의 버그다.
        if ((executionType == ExecutionType.REPLAY) != (replayOfFeedbackId != null)) {
            throw new IllegalArgumentException("replay 와 replayOfFeedbackId 는 짝이어야 한다: " + executionType);
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
        degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
    }

    /**
     * 리졸버가 준 것과 백엔드가 고친 것을 함께 남긴다.
     *
     * <p>둘을 따로 두는 이유는 {@code resolver_output_json} 이 「교정 전」 기록이기 때문이다 (§7.2). 검증 후 값만 남기면 리졸버가 실제로 무엇을 주장했는지
     * 사라져, 리졸버 품질을 나중에 따져볼 수 없다.
     */
    public record ResolverOutput(
            QueryResolution rawResolution,
            QueryResolution verifiedResolution,
            String resolutionSchemaVersion,
            String promptVersion,
            String modelVersion) {}
}
