package com.npick.common.response;

public enum CommonSuccessCode {
    OK("COMM_200", "요청을 처리했습니다."),
    CREATED("COMM_201", "요청한 항목을 생성했습니다.");

    private final String code;
    private final String message;

    CommonSuccessCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
