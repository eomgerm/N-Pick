package com.npick.clip.application.error;

import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum TranscriptErrorCode implements ErrorCode {
    INVALID_SUBTITLE(ErrorType.BAD_REQUEST, "CLIP_400_012", "자막 입력이 올바르지 않습니다."),
    STORAGE_FAILED(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_010", "자막 산출물을 준비하지 못했습니다."),
    CLEANUP_FAILED(ErrorType.INTERNAL_SERVER_ERROR, "CLIP_500_003", "미보존 자막 산출물을 정리하지 못했습니다.");

    private final ErrorType type;
    private final String code;
    private final String message;

    TranscriptErrorCode(ErrorType type, String code, String message) {
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

    /** 위치와 사유는 파서가 생성한다. 원문·파일명·서버 경로·외부 예외 메시지는 넣지 않는다. */
    public static BusinessException invalid(String location, String reason) {
        return new BusinessException(new InputError(location + ": " + reason));
    }

    private record InputError(String message) implements ErrorCode {
        public String code() {
            return INVALID_SUBTITLE.code();
        }

        public ErrorType type() {
            return INVALID_SUBTITLE.type();
        }
    }
}
