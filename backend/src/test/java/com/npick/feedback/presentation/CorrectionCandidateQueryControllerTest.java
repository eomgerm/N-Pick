package com.npick.feedback.presentation;

import java.util.List;

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
import com.npick.feedback.application.GetCorrectionCandidatesUseCase;
import com.npick.feedback.application.query.CorrectionCandidates;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CorrectionCandidateQueryController.class)
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
class CorrectionCandidateQueryControllerTest {

    private static final AuthenticatedMember REVIEWER = new AuthenticatedMember(200L, "reviewer01", "h", "REVIEWER");

    // TSID 크기 — JavaScript 안전 정수(9.0e15)를 넘으므로 문자열로 나가야 정밀도가 보존된다.
    private static final long BIG = 460_000_000_000_000_001L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    GetCorrectionCandidatesUseCase service;

    @Test
    @DisplayName("대기 후보를 십진 문자열 id 와 JSON 객체 condition·patch 로 돌려준다")
    void returnsCandidatesWithStringIds() throws Exception {
        given(service.get(1L, 200L))
                .willReturn(new CorrectionCandidates(
                        List.of(new CorrectionCandidates.TagCandidate(
                                BIG, BIG + 1, "REJECT", "SCENE", "location", "서울", "서울")),
                        List.of(new CorrectionCandidates.ParsePatchCandidate(
                                BIG + 2, "{\"version\":\"parse-rule/v1\"}", "{\"ops\":[]}", BIG + 3)),
                        List.of(new CorrectionCandidates.SceneExcludeCandidate(BIG + 4, BIG + 5))));

        mockMvc.perform(get("/api/v1/review/inquiries/1/correction-candidates").with(user(REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[0].evidenceId").value(String.valueOf(BIG)))
                .andExpect(jsonPath("$.data.tags[0].taggingId").value(String.valueOf(BIG + 1)))
                .andExpect(jsonPath("$.data.tags[0].action").value("REJECT"))
                .andExpect(jsonPath("$.data.tags[0].scope").value("SCENE"))
                .andExpect(jsonPath("$.data.tags[0].tagType").value("location"))
                .andExpect(jsonPath("$.data.tags[0].matchValue").value("서울"))
                .andExpect(jsonPath("$.data.tags[0].displayName").value("서울"))
                .andExpect(jsonPath("$.data.parsePatches[0].searchRuleId").value(String.valueOf(BIG + 2)))
                .andExpect(jsonPath("$.data.parsePatches[0].condition.version").value("parse-rule/v1"))
                .andExpect(jsonPath("$.data.parsePatches[0].patch.ops").isArray())
                .andExpect(jsonPath("$.data.parsePatches[0].replacesRuleId").value(String.valueOf(BIG + 3)))
                .andExpect(jsonPath("$.data.sceneExcludes[0].searchRuleId").value(String.valueOf(BIG + 4)))
                .andExpect(jsonPath("$.data.sceneExcludes[0].targetSceneId").value(String.valueOf(BIG + 5)));
    }

    @Test
    @DisplayName("교체 대상이 없으면 replacesRuleId 는 null 이다")
    void nullReplacesRuleId() throws Exception {
        given(service.get(1L, 200L))
                .willReturn(new CorrectionCandidates(
                        List.of(),
                        List.of(new CorrectionCandidates.ParsePatchCandidate(7L, "{}", "{}", null)),
                        List.of()));

        mockMvc.perform(get("/api/v1/review/inquiries/1/correction-candidates").with(user(REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parsePatches[0].replacesRuleId").value(nullValue()))
                .andExpect(jsonPath("$.data.tags").isEmpty())
                .andExpect(jsonPath("$.data.sceneExcludes").isEmpty());
    }

    @Test
    @DisplayName("편집기자는 보안 계층에서 403 이고 서비스에 닿지 않는다")
    void editorForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/review/inquiries/1/correction-candidates")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR"))))
                .andExpect(status().isForbidden());
        verify(service, never()).get(anyLong(), anyLong());
    }

    @Test
    @DisplayName("담당 검수자가 아니면 403 FEEDBACK_403_002")
    void notAssignedReviewer() throws Exception {
        given(service.get(1L, 200L)).willThrow(new FeedbackException(FeedbackErrorCode.NOT_REVIEWER));

        mockMvc.perform(get("/api/v1/review/inquiries/1/correction-candidates").with(user(REVIEWER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FEEDBACK_403_002"));
    }

    @Test
    @DisplayName("없는 신고면 404 FEEDBACK_404_002")
    void feedbackNotFound() throws Exception {
        given(service.get(1L, 200L)).willThrow(new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));

        mockMvc.perform(get("/api/v1/review/inquiries/1/correction-candidates").with(user(REVIEWER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEEDBACK_404_002"));
    }
}
