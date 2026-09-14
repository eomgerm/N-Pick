package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 장면 제외 후보 생성(S15P21A501-82) 실패. patch_parse 후보의 {@link ParseRuleCandidateErrorCode} 와 대칭이되 제외 고유 규칙(대상 장면 일치)을 더한다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SceneExcludeCandidateErrorCode implements ErrorCode {
    /** 검수자가 아니다. 편집기자는 후보를 만들 수 없다 (F-11). */
    EDITOR_FORBIDDEN(ErrorType.FORBIDDEN, "SRCH_403_211", "검수자만 장면 제외 후보를 만들 수 있다"),
    /** 담당 검수자가 아니다 (F-09). */
    NOT_REVIEWER(ErrorType.FORBIDDEN, "SRCH_403_212", "담당 검수자만 장면 제외 후보를 만들 수 있다"),
    /** 대상 신고가 없다. */
    FEEDBACK_NOT_FOUND(ErrorType.NOT_FOUND, "SRCH_404_211", "대상 신고를 찾을 수 없다"),
    /** 검수 중이 아니다. */
    NOT_REVIEWING(ErrorType.CONFLICT, "SRCH_409_211", "검수 중인 신고에서만 장면 제외 후보를 만들 수 있다"),
    /** 장면 제외(exclude_scene)로 처리된 신고가 아니다. */
    NOT_EXCLUDE_SCENE(ErrorType.CONFLICT, "SRCH_409_212", "장면 제외로 처리된 신고가 아니다"),
    /** 신고가 참조한 장면과 다른 장면을 제외 대상으로 지정했다 (FR-FBK-022). */
    WRONG_TARGET_SCENE(ErrorType.BAD_REQUEST, "SRCH_400_211", "신고 장면과 다른 장면은 제외할 수 없다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
