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
import com.npick.feedback.application.FeedbackIntakeService;
import com.npick.feedback.domain.model.Feedback;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FeedbackController.class)
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
class FeedbackControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FeedbackIntakeService intakeService;

    @Test
    @DisplayName("인증된 사용자는 결과에 신고를 접수한다")
    void authenticatedUserSubmitsInquiry() throws Exception {
        given(intakeService.submit(eq(7L), eq(20L), any())).willReturn(Feedback.open(7L, 20L, "이상해요"));
        mockMvc.perform(post("/api/v1/search/results/7/inquiries")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"comment\":\"이상해요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    @DisplayName("미인증 접수는 401")
    void unauthenticatedSubmitIs401() throws Exception {
        mockMvc.perform(post("/api/v1/search/results/7/inquiries")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("검수 시작된 문의 수정은 409")
    void editReviewingIs409() throws Exception {
        org.mockito.BDDMockito.willThrow(new com.npick.feedback.domain.error.FeedbackException(
                        com.npick.feedback.domain.error.FeedbackErrorCode.NOT_EDITABLE))
                .given(intakeService)
                .editComment(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(20L),
                        org.mockito.ArgumentMatchers.any());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/inquiries/1")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"comment\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("문의 접수는 2,000자까지 허용한다")
    void submitAllows2000Chars() throws Exception {
        String comment = "가".repeat(2000);
        given(intakeService.submit(eq(7L), eq(20L), any())).willReturn(Feedback.open(7L, 20L, comment));
        mockMvc.perform(post("/api/v1/search/results/7/inquiries")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"comment\":\"" + comment + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("문의 접수 2,001자는 저장 전에 400으로 거절한다")
    void submitRejects2001Chars() throws Exception {
        String comment = "가".repeat(2001);
        mockMvc.perform(post("/api/v1/search/results/7/inquiries")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"comment\":\"" + comment + "\"}"))
                .andExpect(status().isBadRequest());
        verify(intakeService, never()).submit(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("문의 수정 2,001자는 저장 전에 400으로 거절한다")
    void editRejects2001Chars() throws Exception {
        String comment = "가".repeat(2001);
        mockMvc.perform(patch("/api/v1/inquiries/1")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"comment\":\"" + comment + "\"}"))
                .andExpect(status().isBadRequest());
        verify(intakeService, never()).editComment(anyLong(), anyLong(), any());
    }
}
