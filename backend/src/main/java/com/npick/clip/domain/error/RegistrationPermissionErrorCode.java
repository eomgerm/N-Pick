package com.npick.clip.domain.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum RegistrationPermissionErrorCode implements ErrorCode {
    RIGHTS_NOT_CONFIRMED("CLIP_400_009", "원본·파생 자료의 이용 권한을 확인해 주세요."),
    EXTERNAL_NOT_CONFIRMED("CLIP_400_010", "현재 처리 설정에는 외부 AI 이용 동의가 필요합니다.");
    private final String code;
    private final String message;

    RegistrationPermissionErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public ErrorType type() {
        return ErrorType.BAD_REQUEST;
    }
}
