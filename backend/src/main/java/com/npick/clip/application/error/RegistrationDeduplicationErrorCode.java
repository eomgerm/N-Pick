package com.npick.clip.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum RegistrationDeduplicationErrorCode implements ErrorCode {
    KEY_CONFLICT("CLIP_409_001", "같은 요청 키에 다른 등록 내용이 있습니다. 새 요청 키를 사용해 주세요."),
    IN_PROGRESS("CLIP_409_002", "영상 등록을 처리 중입니다. 같은 요청으로 다시 확인해 주세요."),
    RESULT_DELETED("CLIP_409_003", "기존 등록 영상이 삭제되었습니다. 새 요청 키를 사용해 주세요.");

    private final String code;
    private final String message;

    RegistrationDeduplicationErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public ErrorType type() {
        return ErrorType.CONFLICT;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
