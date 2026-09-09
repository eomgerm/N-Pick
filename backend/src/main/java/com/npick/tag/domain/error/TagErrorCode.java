package com.npick.tag.domain.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 태그 판정이 우리 데이터의 계약 위반을 만난 경우 (설계 정본 §13).
 *
 * <p>전부 5xx 다. 여기 오는 값은 사용자가 입력한 것이 아니라 <b>우리 코드가 저장하거나 만든 값</b> 이므로, 4xx 로 두면 편집기자가 "검색어가 잘못됐다" 는 안내를 받고 정작 서버 결함은
 * 집계되지 않는다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum TagErrorCode implements ErrorCode {

    /**
     * 저장된 {@code tag.tag_type} 이 FRD F-04 의 11종에 없다.
     *
     * <p>{@code tag_type} 에는 값을 제한하는 {@code CHECK} 가 없다 (날짜 형식 {@code CHECK} 만 있다). 그래서 이 상황은 실제로 가능하다.
     *
     * <p>조용히 그 행을 버리지 않는 이유: 태그가 검색에서 사라지는데 아무 신호가 남지 않는다. FRD 가 반복해서 경고하는 "오류 없이 조용히 안 걸린다" 가 정확히 그 상태다.
     */
    UNKNOWN_TAG_TYPE(ErrorType.INTERNAL_SERVER_ERROR, "TAG_500_001", "태그 종류를 알 수 없다"),

    /**
     * 태그 조회 조건의 범위가 뒤집혔거나 비어 있다.
     *
     * <p>이 값은 질의 리졸버의 구조화 출력을 우리 코드가 옮겨 만든 것이다. 뒤집힌 범위는 조용히 0건이 되므로 "검색 결과 없음" 으로 위장된다.
     */
    INVALID_TAG_CONDITION(ErrorType.INTERNAL_SERVER_ERROR, "TAG_500_002", "태그 조회 조건을 만드는 과정이 잘못됐다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
