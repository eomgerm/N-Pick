package com.npick.clip.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum VideoStorageErrorCode implements ErrorCode {
    STORAGE_FAILED(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_004", "영상 파일을 저장하지 못했습니다."),
    DESTINATION_EXISTS(ErrorType.CONFLICT, "CLIP_409_001", "해당 영상의 저장 위치가 이미 존재합니다."),
    CLEANUP_FAILED(ErrorType.INTERNAL_SERVER_ERROR, "CLIP_500_002", "등록에 실패한 원본 파일을 정리하지 못했습니다.");

    private final ErrorType type;
    private final String code;
    private final String message;

    VideoStorageErrorCode(ErrorType type, String code, String message) {
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
