package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 검색 실행 기록의 조회 실패와 실행이 중간에 끊긴 이유 (FRD §6.2).
 *
 * <p>503 들은 전부 <b>검색 실패</b>다. 일부 기능만 빠진 경우는 {@code SearchDegradedReason} 이 맡고 200 으로 나간다. 둘을 가르는 기준은 §6.2 가 정한 「결과를 줄 수
 * 있는가」 하나다 — 줄 수 있으면 degraded, 없으면 여기. 결과 0건으로 위장하지 않는다. 검색이 실패했는데 빈 배열의 성공 응답을 주면 사용자는 「그런 장면이 없다」로 읽는다 (F-06 완료 기준).
 *
 * <p>코드 prefix 는 저장소의 다른 검색 코드와 같은 {@code SRCH_} 다 ({@code SearchErrorCode} · {@code QueryResolverErrorCode} ·
 * {@code SearchFusionErrorCode} · {@code SceneCandidateErrorCode} · {@code DenseSearchErrorCode} ·
 * {@code SearchRuleErrorCode}).
 *
 * <p>503 세 값은 검색 조립(S15P21A501-59)이 {@link com.npick.search.application.port.SearchExecutionRecordPort#fail} 에 실어 보내는
 * 어휘다. 기록을 남기는 쪽과 그 이유를 만드는 쪽이 다르므로 값은 기록 계약과 같은 곳에 둔다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchExecutionErrorCode implements ErrorCode {

    /**
     * 실행 기록을 조회할 수 없다.
     *
     * <p>타인 소유 실행과 존재하지 않는 실행을 구분하지 않는다. 구분하면 남의 검색이 있었다는 사실 자체가 새어 나간다 (FRD §6.4).
     */
    NOT_FOUND(ErrorType.NOT_FOUND, "SRCH_404_001", "검색 실행 기록을 찾을 수 없습니다."),

    /**
     * 승인된 해석 규칙을 읽지 못했다.
     *
     * <p>§6.2 「활성 규칙 조회 실패 → 사람의 결정을 조용히 건너뛰지 않고 검색 실패·재시도 안내」. 규칙 없이 검색하면 검수자가 승인한 교정이 빠진 결과가 정상인 것처럼 나간다.
     */
    ACTIVE_RULE_LOOKUP_FAILED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_011", "승인된 해석 규칙을 읽지 못했다"),

    /**
     * 기본 단어 검색조차 하지 못했다.
     *
     * <p>§6.2 「기본 단어 검색도 불가 → 검색 실패. 정상적인 0건으로 표시하지 않음」. BM25 는 모든 경로의 바닥이라 이것이 실패하면 줄 결과가 없다.
     */
    LEXICAL_SEARCH_FAILED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_012", "기본 단어 검색을 수행하지 못했다"),

    /**
     * 실행을 열지 못했다.
     *
     * <p>§6.2 「검색 실행의 최초 저장 실패 → AI 호출 전에 중단하고 재시도 안내」. 결과 계산 <b>뒤</b>의 저장 실패와 다르다 — 그쪽은 계산이 이미 끝나 결과를 미저장 상태로 줄 수 있으므로
     * degraded({@code snapshot_save_failed}) 다.
     */
    EXECUTION_NOT_RECORDED(ErrorType.SERVICE_UNAVAILABLE, "SRCH_503_013", "검색 실행을 기록하지 못해 중단했다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
