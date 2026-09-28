package com.npick.common.security.config;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** 요청에 따른 idle 연장과 무관하게 access 인증의 절대 수명을 검사한다. */
public class AccessSessionExpiryFilter extends OncePerRequestFilter {
    public static final String EXPIRES_AT = "NPICK_ACCESS_EXPIRES_AT";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 갱신은 만료 access의 인증 상태가 아니라 별도 refresh 자격 증명만 검증한다.
        return request.getRequestURI().equals("/api/v1/auth/refresh");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null) {
            Object expires = session.getAttribute(EXPIRES_AT);
            if (!(expires instanceof Long deadline) || deadline <= System.currentTimeMillis()) session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
