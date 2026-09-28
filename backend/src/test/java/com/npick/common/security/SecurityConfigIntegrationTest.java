package com.npick.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.common.config.WebConfig;
import com.npick.common.error.handler.ApiErrorResponseWriter;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.security.config.SecurityConfig;
import com.npick.common.security.config.SecurityWebMvcConfig;
import com.npick.common.security.handler.RestAccessDeniedHandler;
import com.npick.common.security.handler.RestAuthenticationEntryPoint;
import com.npick.common.security.resolver.CurrentMemberArgumentResolver;
import com.npick.common.security.support.PingController;
import com.npick.member.infrastructure.security.MemberUserDetailsService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfig 의 deny-by-default·공개 경로 규칙을 {@code @WebMvcTest} 슬라이스로 검증한다.
 *
 * <p>보안 규칙만 보는 테스트라 DB 를 띄우지 않는다. 웹+보안 슬라이스만 올리고 영속 빈은 {@link MemberUserDetailsService} 목으로 대체한다.
 */
@WebMvcTest(controllers = PingController.class)
@Import({
    SecurityConfig.class,
    WebConfig.class,
    SecurityWebMvcConfig.class,
    CurrentMemberArgumentResolver.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    ApiErrorResponseWriter.class,
    ErrorTypeHttpStatusMapper.class
})
class SecurityConfigIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MemberUserDetailsService memberUserDetailsService;

    @Test
    @DisplayName("미인증 보호경로는 401")
    void unauthenticatedProtectedPathReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/review/ping")).andExpect(status().isUnauthorized());
    }

    /**
     * 보안 필터의 실패 응답은 {@code @RestControllerAdvice} 를 거치지 않고 {@link ApiErrorResponseWriter} 가 직접 쓴다. 그 경로로 한국어 message 가
     * 깨지지 않고 나가는지는 상태 코드 검증만으로는 알 수 없다.
     */
    @Test
    @DisplayName("보안 필터 실패 응답의 message 는 한국어로 나간다")
    void securityFailureMessageIsKorean() throws Exception {
        mockMvc.perform(get("/api/v1/review/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMM_401"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다. 로그인 후 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("공개경로는 401 아님")
    void publicPathIsNot401() throws Exception {
        // /api/v1/auth/csrf 컨트롤러는 Task 7에서 추가된다. 여기서는 permitAll 규칙만 검증하므로
        // 핸들러가 없어 404가 나더라도 401(인증 요구)만 아니면 된다.
        mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNotFound());
    }
}
