package com.npick.clip.domain.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum VideoAdmissionErrorCode implements ErrorCode {
    FILE_TOO_LARGE("CLIP_400_005", "영상 파일이 설정된 크기 제한을 초과했습니다."),
    VIDEO_TOO_LONG("CLIP_400_006", "영상 길이가 설정된 제한을 초과했습니다."),
    INVALID_DURATION("CLIP_400_007", "영상의 재생 길이를 확인할 수 없습니다."),
    UNSUPPORTED_MEDIA("CLIP_400_008", "허용되지 않은 영상 형식 또는 코덱입니다.");
    private final String code;
    private final String message;

    VideoAdmissionErrorCode(String code, String message) {
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
