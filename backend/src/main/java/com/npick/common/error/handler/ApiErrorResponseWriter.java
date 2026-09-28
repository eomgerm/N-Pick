package com.npick.common.error.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.npick.common.error.ErrorCode;
import com.npick.common.response.ApiResponse;

@Component
public class ApiErrorResponseWriter {

    private final ObjectMapper objectMapper;
    private final ErrorTypeHttpStatusMapper statusMapper;

    public ApiErrorResponseWriter(ObjectMapper objectMapper, ErrorTypeHttpStatusMapper statusMapper) {
        this.objectMapper = objectMapper;
        this.statusMapper = statusMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode)
            throws IOException {
        response.setStatus(statusMapper.map(errorCode.type()).value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.failure(errorCode, request.getMethod() + " " + request.getRequestURI()));
    }
}
