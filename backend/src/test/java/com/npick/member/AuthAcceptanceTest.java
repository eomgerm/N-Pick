package com.npick.member;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
import com.npick.common.security.support.PingController;
import com.npick.member.infrastructure.security.MemberUserDetailsService;
import com.npick.member.presentation.AuthController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인가·수용 기준(AC) 통합 검증: 역할 거부, 로그아웃 무효화, 비밀번호 해시.
 *
 * <p>Ruling B(web-test-override.md)에 따라 {@code @WebMvcTest} 웹+보안 슬라이스로 검증한다. {@code @SpringBootTest} 는 테스트 환경에서
 * DataSource/JPA 자동설정이 꺼져 있어 부팅에 실패하므로 사용하지 않는다.
 *
 * <p>ID 위조 불가는 {@code AuthControllerTest#meReturnsSessionUser} 가 이미 커버하므로 여기서는 재검증하지 않는다.
 */
@WebMvcTest(controllers = {PingController.class, AuthController.class})
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
class AuthAcceptanceTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    MemberUserDetailsService memberUserDetailsService;

    @BeforeEach
    void setUp() {
        given(memberUserDetailsService.loadUserByUsername("reviewer01"))
                .willReturn(new AuthenticatedMember(200L, "reviewer01", passwordEncoder.encode("pw"), "REVIEWER"));
    }

    @Test
    @DisplayName("편집기자는 검수자 전용 경로에서 403")
    void editorGets403OnReviewerOnlyPath() throws Exception {
        mockMvc.perform(get("/api/v1/review/ping").with(user("editor01").roles("EDITOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("검수자는 검수자 전용 경로 허용")
    void reviewerAllowedOnReviewerOnlyPath() throws Exception {
        mockMvc.perform(get("/api/v1/review/ping").with(user("reviewer01").roles("REVIEWER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("검색 경로는 두 역할 모두 허용")
    void searchPathAllowedForBothRoles() throws Exception {
        mockMvc.perform(get("/api/v1/search/ping").with(user("editor01").roles("EDITOR")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search/ping").with(user("reviewer01").roles("REVIEWER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그아웃하면 보호경로 사용불가")
    void logoutBlocksProtectedPath() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"loginId\":\"reviewer01\",\"password\":\"pw\"}"))
                .andReturn()
                .getRequest()
                .getSession(false);

        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/review/ping").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비밀번호는 평문으로 저장되지 않는다")
    void passwordIsNotStoredInPlaintext() {
        String encoded = passwordEncoder.encode("pw");
        assertThat(encoded).isNotEqualTo("pw");
        assertThat(passwordEncoder.matches("pw", encoded)).isTrue();
    }
}
