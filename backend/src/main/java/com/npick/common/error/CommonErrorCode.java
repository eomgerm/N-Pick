package com.npick.common.error;

public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(ErrorType.BAD_REQUEST, "COMM_400", "Invalid request"),
    VALIDATION_FAILED(ErrorType.BAD_REQUEST, "COMM_400_001", "Request validation failed"),
    UNAUTHORIZED(ErrorType.UNAUTHORIZED, "COMM_401", "Authentication is required"),
    FORBIDDEN(ErrorType.FORBIDDEN, "COMM_403", "Access is denied"),
    NOT_FOUND(ErrorType.NOT_FOUND, "COMM_404", "Resource not found"),
    INTERNAL_SERVER_ERROR(ErrorType.INTERNAL_SERVER_ERROR, "COMM_500", "An unexpected server error occurred");

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
