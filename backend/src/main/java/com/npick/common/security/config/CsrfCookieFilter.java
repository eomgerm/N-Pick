package com.npick.common.security.config;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@link CsrfToken}은 지연 로딩된다: 요청 중 실제로 {@code getToken()}이 호출될 때만 {@code XSRF-TOKEN} 쿠키가 써진다. SPA는 로그인 전 GET 요청만으로 토큰을
 * 받아야 하므로, 매 요청마다 강제로 resolve해 쿠키가 항상 발급되게 한다. (Spring Security 공식 SPA 연동 패턴)
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
