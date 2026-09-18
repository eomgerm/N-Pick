package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 후보 검증 자동 재검색(S15P21A501-83) 실패. {@link ParseRuleCandidateErrorCode}·{@link SceneExcludeCandidateErrorCode}
 * 와 같은 성격(담당 검수자·신고 상태)이되, 검증은 처리 결과(resolution) 종류를 가리지 않고 「대기 후보가 있는가」만 본다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum VerificationErrorCode implements ErrorCode {
    /** 담당 검수자가 아니다 (F-09). */
    NOT_REVIEWER(ErrorType.FORBIDDEN, "SRCH_403_231", "담당 검수자만 검증 재검색을 할 수 있다"),
    /** 대상 신고가 없다. */
    FEEDBACK_NOT_FOUND(ErrorType.NOT_FOUND, "SRCH_404_231", "대상 신고를 찾을 수 없다"),
    /** 검수 중이 아니다. */
    NOT_REVIEWING(ErrorType.CONFLICT, "SRCH_409_231", "검수 중인 신고에서만 검증 재검색을 할 수 있다"),
    /** 대기 중인 교정 후보(태그·해석 patch·장면 제외)가 없다 — 빈 후보로 원 질의만 조용히 재검색하지 않는다. */
    NO_PENDING_CANDIDATES(ErrorType.CONFLICT, "SRCH_409_232", "대기 중인 교정 후보가 없다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
