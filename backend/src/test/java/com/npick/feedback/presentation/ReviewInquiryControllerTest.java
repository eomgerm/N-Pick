package com.npick.feedback.presentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.npick.feedback.application.query.ExecutionSnapshot;
import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.application.query.InquiryScene;
import com.npick.feedback.application.query.ReviewHistory;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
        com.npick.feedback.application.query.InquiryListItem item =
                new com.npick.feedback.application.query.InquiryListItem(
                        1L,
                        "OPEN",
                        null,
                        java.time.Instant.parse("2026-09-08T00:00:00Z"),
                        "질의",
                        new InquiryScene(42L, 123L, "뉴스9 교통 상황", 42_000L, 49_000L, 3001L, 3),
                        true);
        given(reviewService.list(eq("open"), anyInt(), anyInt()))
                .willReturn(new com.npick.feedback.application.query.InquiryListPage(
                        java.util.List.of(item), 6L, new com.npick.feedback.application.query.StatusCounts(3, 2, 1)));
        mockMvc.perform(get("/api/v1/review/inquiries?status=open")
                        .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(6))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.statusCounts.open").value(3))
                .andExpect(jsonPath("$.data.statusCounts.reviewing").value(2))
                .andExpect(jsonPath("$.data.statusCounts.closed").value(1))
                .andExpect(jsonPath("$.data.items[0].feedbackId").value(1))
                .andExpect(jsonPath("$.data.items[0].scene.sceneId").value(42))
                .andExpect(jsonPath("$.data.items[0].scene.clipId").value(123))
                .andExpect(jsonPath("$.data.items[0].scene.clipTitle").value("뉴스9 교통 상황"))
                .andExpect(jsonPath("$.data.items[0].scene.startTimeMs").value(42_000))
                .andExpect(jsonPath("$.data.items[0].scene.endTimeMs").value(49_000))
                .andExpect(jsonPath("$.data.items[0].scene.pipelineRunId").value(3001))
                .andExpect(jsonPath("$.data.items[0].scene.processingNo").value(3));
    }

    @Test
    @DisplayName("page가 음수여도 500 대신 0으로 보정해 정상 응답한다")
    void negativePageIsClampedToZero() throws Exception {
        given(reviewService.list(eq("open"), eq(0), anyInt()))
                .willReturn(new com.npick.feedback.application.query.InquiryListPage(
                        java.util.List.of(), 0L, new com.npick.feedback.application.query.StatusCounts(0, 0, 0)));
        mockMvc.perform(get("/api/v1/review/inquiries?status=open&page=-1")
                        .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("편집기자는 검수 상세 접근이 403")
    void editorForbiddenOnDetail() throws Exception {
        mockMvc.perform(get("/api/v1/review/inquiries/1")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("검수자는 문의 상세를 조회한다")
    void reviewerGetsDetail() throws Exception {
        InquiryDetail detail = new InquiryDetail(
                1L,
                "REVIEWING",
                null,
                null,
                java.time.Instant.parse("2026-09-08T00:00:00Z"),
                "이상해요",
                new InquiryScene(42L, 123L, null, 42_000L, 49_000L, 3001L, 3),
                3,
                "{\"score\":1}",
                new ExecutionSnapshot("query", "{\"date\":\"2026\"}", "{}", "{}", "{}", "{}"),
                java.util.List.of(new com.npick.feedback.application.query.SceneEvidence(
                        5L, "사건명", "verified", "verified", "SCENE")),
                new ReviewHistory(200L, "검수자01", "reviewer01", java.time.Instant.parse("2026-09-08T01:00:00Z"), null));
        given(reviewService.detail(1L)).willReturn(detail);
        mockMvc.perform(get("/api/v1/review/inquiries/1")
                        .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultExplainJson").value("{\"score\":1}"))
                .andExpect(jsonPath("$.data.sceneId").value(42))
                .andExpect(jsonPath("$.data.scene.sceneId").value(42))
                .andExpect(jsonPath("$.data.scene.clipId").value(123))
                .andExpect(jsonPath("$.data.scene.clipTitle").value(nullValue()))
                .andExpect(jsonPath("$.data.scene.startTimeMs").value(42_000))
                .andExpect(jsonPath("$.data.scene.endTimeMs").value(49_000))
                .andExpect(jsonPath("$.data.scene.pipelineRunId").value(3001))
                .andExpect(jsonPath("$.data.scene.processingNo").value(3))
                .andExpect(jsonPath("$.data.resultRank").value(3))
                .andExpect(jsonPath("$.data.execution.explicitFiltersJson").value("{\"date\":\"2026\"}"))
                .andExpect(jsonPath("$.data.history.reviewerName").value("검수자01"))
                .andExpect(jsonPath("$.data.history.reviewerLoginId").value("reviewer01"))
                .andExpect(jsonPath("$.data.evidence[0].scope").value("SCENE"));
    }

    @Test
    @DisplayName("존재하지 않는 문의 상세 조회는 404")
    void detailNotFoundIs404() throws Exception {
        willThrow(new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND))
                .given(reviewService)
                .detail(anyLong());
        mockMvc.perform(get("/api/v1/review/inquiries/999")
                        .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("검수자는 문의를 claim한다")
    void reviewerClaimsInquiry() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/claim")
                        .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER")))
                        .with(csrf())
                        .header("Idempotency-Key", "fe-generated-key-123"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("이미 검수 시작된 문의 claim은 409")
    void claimAlreadyClaimedIs409() throws Exception {
        willThrow(new FeedbackException(FeedbackErrorCode.ALREADY_CLAIMED))
                .given(reviewService)
                .claim(anyLong(), anyLong());
        mockMvc.perform(post("/api/v1/review/inquiries/1/claim")
                        .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER")))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("편집기자는 claim 접근이 403")
    void editorForbiddenOnClaim() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/claim")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resolvePut(String body) {
        return put("/api/v1/review/inquiries/1/resolution")
                .with(user(new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    @DisplayName("검수자는 처리 결과를 기록한다(200)")
    void reviewerResolves() throws Exception {
        mockMvc.perform(resolvePut("{\"resolution\":\"patch_parse\"}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("편집기자는 처리 결과 기록이 403")
    void editorForbiddenOnResolve() throws Exception {
        mockMvc.perform(put("/api/v1/review/inquiries/1/resolution")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"no_action\",\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("사유 없는 종료성 판정은 400(NOTE_REQUIRED)")
    void noteRequiredIs400() throws Exception {
        willThrow(new FeedbackException(FeedbackErrorCode.NOTE_REQUIRED))
                .given(reviewService)
                .resolve(anyLong(), anyLong(), any(), any());
        mockMvc.perform(resolvePut("{\"resolution\":\"no_action\"}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("모르는 처리 결과는 400(INVALID_RESOLUTION)")
    void invalidResolutionIs400() throws Exception {
        willThrow(new FeedbackException(FeedbackErrorCode.INVALID_RESOLUTION))
                .given(reviewService)
                .resolve(anyLong(), anyLong(), any(), any());
        mockMvc.perform(resolvePut("{\"resolution\":\"nope\"}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("담당 검수자가 아니면 403(NOT_REVIEWER)")
    void notReviewerIs403() throws Exception {
        willThrow(new FeedbackException(FeedbackErrorCode.NOT_REVIEWER))
                .given(reviewService)
                .resolve(anyLong(), anyLong(), any(), any());
        mockMvc.perform(resolvePut("{\"resolution\":\"patch_parse\"}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("reviewing이 아니면 409(NOT_RESOLVABLE)")
    void notResolvableIs409() throws Exception {
        willThrow(new FeedbackException(FeedbackErrorCode.NOT_RESOLVABLE))
                .given(reviewService)
                .resolve(anyLong(), anyLong(), any(), any());
        mockMvc.perform(resolvePut("{\"resolution\":\"patch_parse\"}")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("사유가 2000자를 넘으면 400(@Size)")
    void noteTooLongIs400() throws Exception {
        String bigNote = "a".repeat(2001);
        mockMvc.perform(resolvePut("{\"resolution\":\"patch_parse\",\"note\":\"" + bigNote + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
