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
    /** 태그 교정 후보를 만들 수 있는 판정이 아니다. tag_correction 과 patch_parse(태그·해석 모두 잘못) 두 경로에서만 태그 변경안을 만든다 (F-09 진단표). */
    NOT_TAG_CORRECTION(ErrorType.CONFLICT, "TAG_409_002", "태그 교정·해석 교정으로 처리된 신고에서만 태그 변경안을 만들 수 있다"),
    /** 변경안이 하나도 없다. */
    EMPTY_OPERATIONS(ErrorType.BAD_REQUEST, "TAG_400_001", "변경안이 비어 있다"),
    /** 태그 유형이 11종에 없다. */
    INVALID_TAG_TYPE(ErrorType.BAD_REQUEST, "TAG_400_002", "알 수 없는 태그 유형이다"),
    /** 정규화 후 match_value 가 비었다. */
    INVALID_MATCH_VALUE(ErrorType.BAD_REQUEST, "TAG_400_003", "정규화 후 태그 값이 비어 있다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
