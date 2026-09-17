package com.npick.search.application.resolution;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorType;
import com.npick.search.application.port.QueryResolutionResult;

/**
 * 검색이 왜 일부 기능을 빼고 돌았는가 (FRD v3.2 §6.1 의 {@code degraded} 상태).
 *
 * <p>{@link #jsonName()} 은 {@code docs/contracts/web-api.md} §5.1 의 {@code degraded_reasons} 문자열이다. 계약이 값 목록을
 * {@code resolver_fallback} · {@code dense_unavailable} · {@code snapshot_save_failed} 로 닫아 두었고, 뒤 둘은 그 사유를 만들 수 있는 코드가
 * 아직 없어 dense 채널({@code S15P21A501-53})과 실행 기록 저장({@code S15P21A501-60})이 각자 붙인다.
 *
 * <p>이 이름을 JSON 으로 옮기는 것은 presentation 의 몫이다 (설계 정본 §3·§4). {@code ParseRuleOutcome.Status} 와 같은 방식으로, 문자열은 여기 두고 변환은
 * 쓰는 쪽에서 드러나게 한다.
 *
 * <p>여기 값들은 응답용 어휘라 {@code search_execution.degraded_reasons_json} 과 같지 않다 — 그 컬럼에는 규칙 판정이
 * {@code "skipped_conflict:<rule_id>"} 처럼 규칙 ID 를 붙인 문자열도 넣는다 ({@code ParseRulePolicy.Result#degradedReasons}). 둘을 어떻게
 * 나란히 저장할지는 {@code S15P21A501-60} 이 정한다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchDegradedReason {
    /** AI 해석 없이 원 검색어 토큰의 단어 검색만으로 결과를 냈다. */
    RESOLVER_FALLBACK("resolver_fallback"),
    /** 텍스트 의미 검색 채널이 돌지 못했다. 단어 검색과 사용 가능한 신호로만 결과를 냈다 (§6.2). */
    DENSE_UNAVAILABLE("dense_unavailable"),
    /** 결과는 계산했는데 기록으로 남기지 못했다. 이 결과로는 신고할 수 없다 (§6.2·§5.1). */
    SNAPSHOT_SAVE_FAILED("snapshot_save_failed");

    private final String jsonName;

    /**
     * 이 해석 결과로 검색을 이어갈 때 <b>질의 해석 경로가 낼</b> 축소 사유.
     *
     * <p>fallback 은 다른 검색 경로가 아니다. 해석이 없을 뿐 BM25 는 같은 {@code normalization.searchTokens()} 로 똑같이 돈다 (FRD v3.2 §6.2 「AI
     * 해석 실패·시간 초과 → 원 검색어의 단어 검색으로 전환하고 일부 기능 누락 안내」). 그래서 여기서 하는 일은 실행 경로를 가르는 것이 아니라 그 축소에 이름을 붙이는 것 하나다.
     *
     * <p>돌려주는 것은 이 경로의 몫뿐이다. 응답의 {@code status} 는 dense 채널·기록 저장이 낸 사유까지 <b>합친 뒤</b> 정해야 한다 (§5.1 불변식).
     * {@link #RESOLVER_FALLBACK} 이 들어간 응답은 {@code query_resolution_status} 도 {@code fallback} 이어야 한다 — 계약이 둘의 일치를 요구한다.
     *
     * <p>{@link com.npick.search.application.port.QueryResolverPort#resolve} 가 <b>의존성 장애로</b> 던지는
     * {@code BusinessException} 은 여기까지 오지 않고 그대로 검색 실패가 된다. 리졸버에 닿지 못했거나 응답에서 정규화조차 읽지 못했다는 뜻이라 단어 검색에 쓸 토큰도
     * {@code normalization_version} 도 없기 때문이다 — 토큰이 없으면 단어 검색을 할 수 없고, 버전이 없으면 지문을 만들 수 없어 승인된 장면 제외 규칙을 조회할 수 없다. 사람의
     * 결정을 조용히 건너뛴 채 결과를 주게 된다 (§6.2 「활성 규칙 조회 실패」·「기본 단어 검색도 불가」 둘 다 검색 실패다).
     *
     * @return 해석에 성공했으면 빈 목록, 리졸버 장애로 실패했으면 {@link #RESOLVER_FALLBACK} 하나
     * @throws BusinessException 실패 사유가 의존성 장애가 아닌 경우. §6.2 가 원 검색어 단어 검색으로 축소하라는 대상은 리졸버 장애뿐이다. <b>정상 경로로는 이런 결과가 오지
     *     않는다</b> — 사용자 입력 문제인 {@link com.npick.search.application.error.QueryResolverErrorCode#QUERY_NOT_NORMALIZABLE}
     *     은 리졸버가 400 으로 답할 때 나오고 그 경로는 어댑터가 예외로 던진다. 이 검사는 <b>AI 응답을 믿지 않기 위한 것</b>이다 —
     *     {@code QueryResolutionApiResponse} 가 {@code error.category} 를 화이트리스트 없이 {@code valueOf} 로 읽으므로, 리졸버가 200 본문에
     *     장애가 아닌 코드를 실어 보내면 그대로 이 자리로 들어온다. 그때 degraded 로 받으면 검색할 단어를 못 만든 질의가 빈 결과의 성공 응답이 된다. 코드 목록이 아니라
     *     {@link ErrorType} 으로 가르는 이유도 같다 — 앞으로 추가될 코드가 조용히 fallback 으로 흘러들지 않게 한다.
     */
    public static List<SearchDegradedReason> reasonsFor(QueryResolutionResult result) {
        if (result.isResolved()) {
            return List.of();
        }
        if (result.failure().type() != ErrorType.SERVICE_UNAVAILABLE) {
            throw new BusinessException(result.failure());
        }
        // 장애 종류를 사유로 세분하지 않는다. 계약이 값 목록을 닫아 두었고, 사용자에게 필요한
        // 것은 "AI 해석 없이 돌았다" 하나다. 분류는 search_execution.error_code 가 기록으로 남긴다.
        return List.of(RESOLVER_FALLBACK);
    }
}
