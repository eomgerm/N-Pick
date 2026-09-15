package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/** 규칙 사용 중단(S15P21A501-86, F-11) 실패. */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchRuleDeactivationErrorCode implements ErrorCode {
    /** 검수자가 아니다. 편집기자는 규칙을 끌 수 없다. */
    EDITOR_FORBIDDEN(ErrorType.FORBIDDEN, "SRCH_403_221", "검수자만 규칙을 사용 중단할 수 있다"),
    /** 대상 규칙이 없다. */
    RULE_NOT_FOUND(ErrorType.NOT_FOUND, "SRCH_404_221", "대상 규칙을 찾을 수 없다"),
    /** 이미 꺼진 규칙이다. */
    ALREADY_INACTIVE(ErrorType.CONFLICT, "SRCH_409_221", "이미 사용 중단된 규칙이다"),
    /** 검증 없이 직접 재활성화하려 했다. 재사용은 현재 조건에서 검증·승인하는 경로(-83→-84)를 지나야 한다 (F-11). */
    CANNOT_REACTIVATE(ErrorType.CONFLICT, "SRCH_409_222", "검증 없이 규칙을 다시 켤 수 없다 — 재검증·승인 경로를 거쳐야 한다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
