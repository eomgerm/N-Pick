package com.npick.pipeline.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum WorkerIntegrationErrorCode implements ErrorCode {
    INVALID_OUTPUT("JOB_400_001", ErrorType.BAD_REQUEST),
    HASH_MISMATCH("JOB_400_002", ErrorType.BAD_REQUEST),
    LENGTH_REQUIRED("JOB_411_001", ErrorType.LENGTH_REQUIRED),
    UNAUTHORIZED("JOB_401", ErrorType.UNAUTHORIZED),
    PATH_FORBIDDEN("JOB_403_001", ErrorType.FORBIDDEN),
    FLEET_FORBIDDEN("JOB_403_002", ErrorType.FORBIDDEN),
    ARTIFACT_MISSING("JOB_404_002", ErrorType.NOT_FOUND),
    STORAGE_UNAVAILABLE("JOB_503_001", ErrorType.SERVICE_UNAVAILABLE);

    private final String code;
    private final ErrorType type;

    WorkerIntegrationErrorCode(String code, ErrorType type) {
        this.code = code;
        this.type = type;
    }

    public String code() {
        return code;
    }

    public String message() {
        return switch (this) {
            case INVALID_OUTPUT -> "단계 산출물의 형식·크기·참조를 확인해주세요.";
            case HASH_MISMATCH -> "산출물의 SHA-256이 전송한 바이트와 일치하지 않습니다.";
            case LENGTH_REQUIRED -> "산출물 업로드에는 Content-Length가 필요합니다.";
            case UNAUTHORIZED -> "유효한 워커 토큰과 워커 ID가 필요합니다.";
            case PATH_FORBIDDEN -> "현재 배정에서 접근할 수 없는 산출물 경로입니다.";
            case FLEET_FORBIDDEN -> "워커 fleet이 이 서버의 fleet과 일치하지 않습니다.";
            case ARTIFACT_MISSING -> "참조한 산출물 파일이 없습니다.";
            case STORAGE_UNAVAILABLE -> "산출물 저장소에 접근할 수 없습니다.";
        };
    }

    public ErrorType type() {
        return type;
    }
}
