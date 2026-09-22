package com.npick.search.presentation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.common.config.WebConfig;
import com.npick.common.error.BusinessException;
import com.npick.common.error.handler.ApiErrorResponseWriter;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.error.handler.GlobalExceptionHandler;
import com.npick.common.security.AuthenticatedMember;
import com.npick.common.security.config.SecurityConfig;
import com.npick.common.security.config.SecurityWebMvcConfig;
import com.npick.common.security.handler.RestAccessDeniedHandler;
import com.npick.common.security.handler.RestAuthenticationEntryPoint;
import com.npick.common.security.resolver.CurrentMemberArgumentResolver;
import com.npick.search.application.error.VerificationErrorCode;
import com.npick.search.application.query.search.SceneDiff;
import com.npick.search.application.query.search.VerificationResult;
import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /review/inquiries/{feedbackId}/verify} (web-api §6.4, S15P21A501-83 Task 6).
 *
 * <p>{@code SceneExcludeCandidateControllerTest}·{@code SearchControllerTest} 와 같은 패턴: {@code @WebMvcTest} + 유스케이스 mock
 * 이다. 실제 재검색 파이프라인·롤백은 {@code VerificationCombinationDbTest} 등 DB 테스트가 이미 검증했으므로 여기서는 컨트롤러가 계약이 정한 응답 모양(snake_case, id
 * 문자열)으로 옮기는지만 본다.
 */
@WebMvcTest(controllers = VerificationController.class)
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
class VerificationControllerTest {

    private static final AuthenticatedMember REVIEWER = new AuthenticatedMember(9001L, "reviewer01", "h", "REVIEWER");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    VerifyCorrectionCandidatesUseCase useCase;

    @Test
    @DisplayName("계약이 정한 data 모양 그대로 응답한다 — 진입/제외 장면과 실행 id 는 문자열")
    void respondsWithTheContractShape() throws Exception {
        given(useCase.verify(anyLong(), anyLong()))
                .willReturn(new VerificationResult(
                        700L,
                        List.of(new SceneDiff.Entered(9302L, Map.of("score", Map.of("base_score", 1.2)))),
                        List.of(new SceneDiff.Dropped(9301L, "score_drop")),
                        List.of(8001L, 8002L)));

        mockMvc.perform(post("/api/v1/review/inquiries/1/verify")
                        .with(user(REVIEWER))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.execution_id").value("700"))
                .andExpect(jsonPath("$.data.execution_id").isString())
                .andExpect(jsonPath("$.data.entered_scenes[0].scene_id").value("9302"))
                .andExpect(jsonPath("$.data.entered_scenes[0].scene_id").isString())
                .andExpect(jsonPath("$.data.entered_scenes[0].reason").exists())
                .andExpect(jsonPath("$.data.dropped_scenes[0].scene_id").value("9301"))
                .andExpect(jsonPath("$.data.dropped_scenes[0].reason").value("score_drop"))
                .andExpect(jsonPath("$.data.verification_rule_set[0]").value("8001"))
                .andExpect(jsonPath("$.data.verification_rule_set[0]").isString());
    }

    @Test
    @DisplayName("편집기자는 검증 재검색 경로에 접근할 수 없다")
    void editorForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/verify")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/verify").with(csrf())).andExpect(status().isUnauthorized());
    }

    /*
     * Fix round 1(authz, S15P21A501-83): 서비스가 던지는 인가/전제 오류가 컨트롤러 경로에서 계약이 정한 HTTP 상태로
     * 옮겨지는지 본다. 가드 순서·오류 코드 선택 자체는 VerificationSearchServiceTest 가 이미 검증했다 — 여기서는
     * GlobalExceptionHandler/ErrorTypeHttpStatusMapper 배선만 확인한다.
     */

    @Test
    @DisplayName("대상 신고가 없으면 404 다")
    void missingFeedbackReturnsNotFound() throws Exception {
        given(useCase.verify(anyLong(), anyLong()))
                .willThrow(new BusinessException(VerificationErrorCode.FEEDBACK_NOT_FOUND));

        mockMvc.perform(post("/api/v1/review/inquiries/1/verify")
                        .with(user(REVIEWER))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("담당 검수자가 아니면 403 이다")
    void nonOwnerReviewerReturnsForbidden() throws Exception {
        given(useCase.verify(anyLong(), anyLong()))
                .willThrow(new BusinessException(VerificationErrorCode.NOT_REVIEWER));

        mockMvc.perform(post("/api/v1/review/inquiries/1/verify")
                        .with(user(REVIEWER))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("검수 중이 아니면 409 다")
    void notReviewingReturnsConflict() throws Exception {
        given(useCase.verify(anyLong(), anyLong()))
                .willThrow(new BusinessException(VerificationErrorCode.NOT_REVIEWING));

        mockMvc.perform(post("/api/v1/review/inquiries/1/verify")
                        .with(user(REVIEWER))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("대기 후보가 없으면 409 다")
    void noPendingCandidatesReturnsConflict() throws Exception {
        given(useCase.verify(anyLong(), anyLong()))
                .willThrow(new BusinessException(VerificationErrorCode.NO_PENDING_CANDIDATES));

        mockMvc.perform(post("/api/v1/review/inquiries/1/verify")
                        .with(user(REVIEWER))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }
}
