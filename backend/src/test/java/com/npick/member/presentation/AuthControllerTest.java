package com.npick.member.presentation;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.npick.common.config.WebConfig;
import com.npick.common.error.handler.ApiErrorResponseWriter;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.error.handler.GlobalExceptionHandler;
import com.npick.common.security.AuthenticatedMember;
import com.npick.common.security.config.SecurityConfig;
import com.npick.common.security.config.SecurityWebMvcConfig;
import com.npick.common.security.handler.RestAccessDeniedHandler;
import com.npick.common.security.handler.RestAuthenticationEntryPoint;
import com.npick.common.security.resolver.CurrentMemberArgumentResolver;
import com.npick.member.infrastructure.security.MemberUserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({
    SecurityConfig.class,
    WebConfig.class,
    SecurityWebMvcConfig.class,
    CurrentMemberArgumentResolver.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    ApiErrorResponseWriter.class,
    ErrorTypeHttpStatusMapper.class,
    GlobalExceptionHandler.class
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    MemberUserDetailsService memberUserDetailsService;

    @BeforeEach
    void setUp() {
        given(memberUserDetailsService.loadUserByUsername("reviewer01"))
                .willReturn(
                        new AuthenticatedMember(100L, "reviewer01", passwordEncoder.encode("pw-correct"), "REVIEWER"));
    }

    @Test
    @DisplayName("유효한 계정은 로그인에 성공하고 비밀번호는 응답에 없다")
    void validAccountLogsInAndResponseHasNoPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"loginId\":\"reviewer01\",\"password\":\"pw-correct\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginId").value("reviewer01"))
                .andExpect(jsonPath("$.data.role").value("REVIEWER"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("잘못된 비밀번호는 401과 한국어 안내")
    void wrongPasswordReturns401WithKoreanMessage() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"loginId\":\"reviewer01\",\"password\":\"pw-wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("me는 세션의 사용자를 반환한다")
    void meReturnsSessionUser() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"loginId\":\"reviewer01\",\"password\":\"pw-correct\"}"))
                .andReturn()
                .getRequest()
                .getSession(false);

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(100));
    }

    // 아래 두 테스트는 실제 CookieCsrfTokenRepository 동작을 검증하므로 .with(csrf())보다 먼저 실행돼야 한다.
    // .with(csrf())는 공유 스프링 컨텍스트의 CsrfFilter#tokenRepository 필드를 세션 기반 테스트 repository로
    // 영구 교체해버려(SecurityMockMvcRequestPostProcessors 구현), 이후 실행되는 실제 쿠키 검증을 무력화한다.
    @Test
    @Order(1)
    @DisplayName("CSRF 경로를 GET하면 XSRF_TOKEN 쿠키가 발급된다")
    void getCsrfPathIssuesXsrfTokenCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie xsrfCookie = result.getResponse().getCookie("XSRF-TOKEN");

        assertThat(xsrfCookie).isNotNull();
        assertThat(xsrfCookie.getValue()).isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("발급받은 CSRF 토큰을 헤더로 실어보내면 with_csrf 없이도 로그인에 성공한다")
    void issuedCsrfTokenInHeaderAllowsLoginWithoutWithCsrf() throws Exception {
        Cookie xsrfCookie = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue())
                        .contentType("application/json")
                        .content("{\"loginId\":\"reviewer01\",\"password\":\"pw-correct\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginId").value("reviewer01"));
    }

    @Test
    @DisplayName("로그인하면 세션ID가 회전한다")
    void loginRotatesSessionId() throws Exception {
        MockHttpSession sessionBeforeLogin = new MockHttpSession();
        String sessionIdBeforeLogin = sessionBeforeLogin.getId();

        MockHttpSession sessionAfterLogin = (MockHttpSession) mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .session(sessionBeforeLogin)
                        .contentType("application/json")
                        .content("{\"loginId\":\"reviewer01\",\"password\":\"pw-correct\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);

        assertThat(sessionAfterLogin.getId()).isNotEqualTo(sessionIdBeforeLogin);
    }
}
