package com.npick.clip.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum VideoPreparationErrorCode implements ErrorCode {
    INVALID_VIDEO(ErrorType.BAD_REQUEST, "CLIP_400_001", "영상이 없거나 정상적으로 읽을 수 없는 파일입니다."),
    INSPECTION_FAILED(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_001", "영상 검사를 수행할 수 없습니다."),
    INSPECTION_TIMED_OUT(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_002", "영상 검사 시간이 초과되어 검증을 완료하지 못했습니다."),
    INSPECTION_INTERRUPTED(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_003", "영상 검사가 중단되었습니다."),
    CLEANUP_FAILED(ErrorType.INTERNAL_SERVER_ERROR, "CLIP_500_001", "영상 임시 파일을 정리하지 못했습니다.");

    private final ErrorType type;
    private final String code;
    private final String message;

    VideoPreparationErrorCode(ErrorType type, String code, String message) {
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
