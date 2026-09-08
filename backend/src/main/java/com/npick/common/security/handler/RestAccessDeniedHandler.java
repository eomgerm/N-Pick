package com.npick.common.security.handler;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.npick.common.error.CommonErrorCode;
import com.npick.common.error.handler.ApiErrorResponseWriter;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorResponseWriter writer;

    public RestAccessDeniedHandler(ApiErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writer.write(request, response, CommonErrorCode.FORBIDDEN);
    }
}
