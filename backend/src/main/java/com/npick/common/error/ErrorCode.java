package com.npick.common.error;

public interface ErrorCode {

    ErrorType type();

    String code();

    String message();
}
