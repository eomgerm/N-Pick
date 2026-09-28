package com.npick.common.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import com.npick.common.error.ErrorCode;

@JsonPropertyOrder({"isSuccess", "code", "message", "timestamp", "path", "data"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        @JsonProperty("isSuccess") boolean isSuccess,
        String code,
        String message,
        T data,
        Instant timestamp,
        String path) {

    public static ApiResponse<Void> success() {
        return success(null, CommonSuccessCode.OK);
    }

    public static <T> ApiResponse<T> success(T data) {
        return success(data, CommonSuccessCode.OK);
    }

    public static <T> ApiResponse<T> success(T data, CommonSuccessCode code) {
        return new ApiResponse<>(true, code.code(), code.message(), data, null, null);
    }

    public static ApiResponse<Void> failure(ErrorCode code, String path) {
        return failure(code, path, null);
    }

    public static <T> ApiResponse<T> failure(ErrorCode code, String path, T data) {
        return new ApiResponse<>(false, code.code(), code.message(), data, Instant.now(), path);
    }
}
