package com.npick.common.response;

public enum CommonSuccessCode {
    OK("COMM_200", "Request succeeded"),
    CREATED("COMM_201", "Resource created");

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
