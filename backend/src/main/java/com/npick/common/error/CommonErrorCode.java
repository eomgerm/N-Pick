package com.npick.common.error;

public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(ErrorType.BAD_REQUEST, "COMM_400", "요청 내용을 확인해 주세요."),
    VALIDATION_FAILED(ErrorType.BAD_REQUEST, "COMM_400_001", "입력한 내용을 확인해 주세요."),
    UNAUTHORIZED(ErrorType.UNAUTHORIZED, "COMM_401", "로그인이 필요합니다. 로그인 후 다시 시도해 주세요."),
    FORBIDDEN(ErrorType.FORBIDDEN, "COMM_403", "권한이 없거나 요청 보안 정보가 만료되었습니다. 다시 시도해도 계속되면 담당자에게 문의해 주세요."),
    NOT_FOUND(ErrorType.NOT_FOUND, "COMM_404", "요청한 항목을 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(ErrorType.INTERNAL_SERVER_ERROR, "COMM_500", "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

    private final ErrorType type;
    private final String code;
    private final String message;

    CommonErrorCode(ErrorType type, String code, String message) {
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
