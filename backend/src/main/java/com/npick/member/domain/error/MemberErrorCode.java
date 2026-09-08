package com.npick.member.domain.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum MemberErrorCode implements ErrorCode {
    INVALID_CREDENTIALS(ErrorType.UNAUTHORIZED, "MEMBER_401_001", "아이디 또는 비밀번호가 올바르지 않습니다.");

    private final ErrorType type;
    private final String code;
    private final String message;

    MemberErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
