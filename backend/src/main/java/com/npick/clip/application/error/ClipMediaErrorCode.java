package com.npick.clip.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/** Preview 재생(FR-RES-013~015)의 실패 어휘. 파일 누락과 구간 오류를 서로 다른 코드로 구분한다. */
public enum ClipMediaErrorCode implements ErrorCode {
    CLIP_NOT_FOUND(ErrorType.NOT_FOUND, "CLIP_404_001", "재생할 영상을 찾을 수 없습니다."),
    MEDIA_FILE_MISSING(ErrorType.NOT_FOUND, "CLIP_404_002", "영상 원본 파일이 없어 재생할 수 없습니다."),
    SCENE_NOT_FOUND(ErrorType.NOT_FOUND, "CLIP_404_003", "다운로드할 장면을 찾을 수 없습니다."),
    RANGE_NOT_SATISFIABLE(ErrorType.RANGE_NOT_SATISFIABLE, "CLIP_416_001", "요청한 재생 구간이 영상 길이를 벗어났습니다."),
    MEDIA_LOCATION_REJECTED(ErrorType.INTERNAL_SERVER_ERROR, "CLIP_500_003", "영상 저장 위치가 올바르지 않습니다."),
    MEDIA_READ_FAILED(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_010", "영상을 전송하지 못했습니다."),
    MEDIA_ROOT_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_011", "영상 저장 위치 설정이 준비되지 않았습니다."),
    MEDIA_EXTRACTION_FAILED(ErrorType.SERVICE_UNAVAILABLE, "CLIP_503_012", "장면 영상을 추출하지 못했습니다.");

    private final ErrorType type;
    private final String code;
    private final String message;

    ClipMediaErrorCode(ErrorType type, String code, String message) {
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
