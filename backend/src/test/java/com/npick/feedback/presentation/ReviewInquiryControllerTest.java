package com.npick.feedback.presentation;

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
import com.npick.common.error.handler.GlobalExceptionHandler;
import com.npick.common.security.AuthenticatedMember;
import com.npick.common.security.config.SecurityConfig;
import com.npick.common.security.config.SecurityWebMvcConfig;
import com.npick.common.security.handler.RestAccessDeniedHandler;
import com.npick.common.security.handler.RestAuthenticationEntryPoint;
import com.npick.common.security.resolver.CurrentMemberArgumentResolver;
import com.npick.feedback.application.InquiryReviewService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReviewInquiryController.class)
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
class ReviewInquiryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InquiryReviewService reviewService;

    @Test
    @DisplayName("편집기자는 검수 목록 접근이 403")
    void editorForbiddenOnList() throws Exception {
        mockMvc.perform(get("/api/v1/review/inquiries")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("검수자는 상태별 목록을 조회한다")
    void reviewerListsByStatus() throws Exception {
        given(reviewService.list(eq("open"), anyInt(), anyInt())).willReturn(java.util.List.of());
        mockMvc.perform(get("/api/v1/review/inquiries?status=open")
                        .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("page가 음수여도 500 대신 0으로 보정해 정상 응답한다")
    void negativePageIsClampedToZero() throws Exception {
        given(reviewService.list(eq("open"), eq(0), anyInt())).willReturn(java.util.List.of());
        mockMvc.perform(get("/api/v1/review/inquiries?status=open&page=-1")
                        .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER"))))
                .andExpect(status().isOk());
    }
}
