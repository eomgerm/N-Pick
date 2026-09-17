package com.npick.common.logging;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 request ID 를 확정해 로그·응답에 싣는다 (S15P21A501-136).
 *
 * <p>들어온 {@code X-Request-Id} 가 있으면 그대로 쓰고, 없거나 공백이면 새로 생성한다. 값을 MDC 에 넣어 처리 중 남는 모든 로그에 붙게 하고
 * 응답 헤더 {@code X-Request-Id} 로 에코한다. 요청이 끝나면 MDC 를 비워, 풀에서 재사용되는 스레드에 값이 새지 않게 한다.
 */
public final class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
