package com.npick.tag.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/** 태그 교정 후보 생성(S15P21A501-160) 실패. */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum TagCorrectionCandidateErrorCode implements ErrorCode {
    /** 검수자가 아니다. 편집기자는 후보를 만들 수 없다 (F-10). */
    EDITOR_FORBIDDEN(ErrorType.FORBIDDEN, "TAG_403_001", "검수자만 태그 교정 후보를 만들 수 있다"),
    /** 담당 검수자가 아니다 (F-09). */
    NOT_REVIEWER(ErrorType.FORBIDDEN, "TAG_403_002", "담당 검수자만 태그 교정 후보를 만들 수 있다"),
    /** 대상 신고가 없다. */
    FEEDBACK_NOT_FOUND(ErrorType.NOT_FOUND, "TAG_404_001", "대상 신고를 찾을 수 없다"),
    /** 검수 중이 아니다. */
    NOT_REVIEWING(ErrorType.CONFLICT, "TAG_409_001", "검수 중인 신고에서만 태그 교정 후보를 만들 수 있다"),
    /** 태그 교정(tag_correction)으로 처리된 신고가 아니다. */
    NOT_TAG_CORRECTION(ErrorType.CONFLICT, "TAG_409_002", "태그 교정으로 처리된 신고가 아니다"),
    /** 변경안이 하나도 없다. */
    EMPTY_OPERATIONS(ErrorType.BAD_REQUEST, "TAG_400_001", "변경안이 비어 있다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
