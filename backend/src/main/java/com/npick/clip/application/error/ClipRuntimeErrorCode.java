package com.npick.clip.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum ClipRuntimeErrorCode implements ErrorCode {
    AUTHENTICATION_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_005", "영상 등록 인증 연동이 준비되지 않았습니다."),
    CONFIGURATION_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_006", "영상 등록 실행 설정이 준비되지 않았습니다."),
    REGISTRATION_FAILED(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_007", "영상 등록 정보를 저장하지 못했습니다."),
    REGISTRATION_OUTCOME_UNKNOWN(
            ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_008", "등록 결과를 확인하지 못했습니다. 같은 요청으로 결과를 확인해 주세요."),
    INTEGRATION_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_009", "영상 등록에 필요한 연계 기능이 준비되지 않았습니다.");
    private final ErrorType type;
    private final String code;
    private final String message;

    ClipRuntimeErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    public ErrorType type() {
        return type;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
