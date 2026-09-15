package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * patch_parse 후보 생성(S15P21A501-81) 실패. 규칙 조회 실패인 {@link SearchRuleErrorCode}(-49)와 책임이 다르다 — 이쪽은 검수자의 후보 작성 요청 검증이다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ParseRuleCandidateErrorCode implements ErrorCode {
    /** 검수자가 아니다. 편집기자는 후보를 만들 수 없다 (F-11). */
    EDITOR_FORBIDDEN(ErrorType.FORBIDDEN, "SRCH_403_201", "검수자만 규칙 후보를 만들 수 있다"),
    /** 담당 검수자가 아니다. claim 한 사람만 만들 수 있다 (F-09). */
    NOT_REVIEWER(ErrorType.FORBIDDEN, "SRCH_403_202", "담당 검수자만 규칙 후보를 만들 수 있다"),
    /** 대상 신고가 없다. */
    FEEDBACK_NOT_FOUND(ErrorType.NOT_FOUND, "SRCH_404_201", "대상 신고를 찾을 수 없다"),
    /** 검수 중이 아니다. */
    NOT_REVIEWING(ErrorType.CONFLICT, "SRCH_409_201", "검수 중인 신고에서만 규칙 후보를 만들 수 있다"),
    /** 해석 교정(patch_parse)으로 처리된 신고가 아니다. */
    NOT_PATCH_PARSE(ErrorType.CONFLICT, "SRCH_409_202", "해석 교정으로 처리된 신고가 아니다"),
    /** 후보 본문(condition/patch)이 문법·출력 계약에 맞지 않는다. */
    INVALID_CANDIDATE(ErrorType.BAD_REQUEST, "SRCH_400_201", "규칙 후보 본문이 올바르지 않다"),
    /** 교체 대상이 켜져 있는 patch_parse 규칙이 아니다 (없음·비활성·다른 action). */
    REPLACES_NOT_FOUND(ErrorType.BAD_REQUEST, "SRCH_400_202", "교체 대상 규칙을 찾을 수 없다"),
    /** 원 검색에 교정 전 AI 해석(resolver_output)이 없어 후보 본문을 대조할 수 없다. */
    RESOLVER_OUTPUT_ABSENT(ErrorType.CONFLICT, "SRCH_409_203", "원 검색에 교정할 해석 출력이 없다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
