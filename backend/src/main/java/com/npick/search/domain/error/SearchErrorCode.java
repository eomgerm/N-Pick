package com.npick.search.domain.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * search 도메인의 불변식 위반 (설계 정본 §13).
 *
 * <p>여기에는 ErrorCode 구현만 둔다. 도메인별 RuntimeException 클래스를 따로 만들지 않고 공통 {@code BusinessException} 을 쓴다.
 *
 * <p>이 값들이 {@code IllegalArgumentException} 이면 GlobalExceptionHandler 의 {@code Exception.class} 폴백에 걸려 입력 문제가 500 과
 * error 레벨 스택트레이스가 된다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchErrorCode implements ErrorCode {
    NORMALIZED_QUERY_BLANK(ErrorType.BAD_REQUEST, "SRCH_400_001", "정규화된 질의가 비어 있다"),
    NORMALIZATION_VERSION_BLANK(ErrorType.BAD_REQUEST, "SRCH_400_002", "정규화 버전이 비어 있다"),
    FILTER_CONTAINS_NULL(ErrorType.BAD_REQUEST, "SRCH_400_003", "필터에 널 값이 들어 있다"),
    EXPLICIT_FILTER_RANGE_INVERTED(ErrorType.BAD_REQUEST, "SRCH_400_004", "명시 필터의 시작일이 종료일보다 늦다"),

    /**
     * 해석 규칙에 본문과 파싱 실패 사유 중 하나만 있어야 하는데 둘 다거나 둘 다 없다.
     *
     * <p>규칙 JSON 의 내용 문제가 아니다 — 그것은 {@code ParseRule.incompatibleReason()} 이 사유와 함께 {@code skipped_incompatible} 로
     * 기록한다. 이쪽은 {@code ParseRule} 을 잘못 만든 코드 버그다.
     */
    RULE_BODY_INCOMPLETE(ErrorType.INTERNAL_SERVER_ERROR, "SRCH_500_004", "해석 규칙 본문이 온전하지 않다"),

    /** 스칼라 축({@code intent})을 목록으로 읽거나 쓰려 했다. 축과 연산의 조합은 규칙 판정 전에 걸러지므로 여기 오면 코드 버그다. */
    RULE_AXIS_NOT_A_LIST(ErrorType.INTERNAL_SERVER_ERROR, "SRCH_500_005", "목록이 아닌 축을 목록으로 다뤘다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
