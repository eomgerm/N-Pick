package com.npick.search.presentation;

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
import com.npick.search.application.CreateSceneExcludeCandidateService;
import com.npick.search.application.ParseCandidateOutcome;

import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SceneExcludeCandidateController.class)
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
class SceneExcludeCandidateControllerTest {

    private static final String BODY = "{\"targetSceneId\":\"300\"}";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CreateSceneExcludeCandidateService service;

    private static final AuthenticatedMember REVIEWER = new AuthenticatedMember(9L, "reviewer01", "h", "REVIEWER");

    @Test
    @DisplayName("검수자가 새 제외 후보를 만들면 201 과 생성 id 를 준다")
    void reviewerCreatesCandidate() throws Exception {
        given(service.create(any())).willReturn(ParseCandidateOutcome.created(777L));

        mockMvc.perform(post("/api/v1/review/inquiries/1/scene-exclude-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .header("Idempotency-Key", "rk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.searchRuleId").value("777"))
                .andExpect(jsonPath("$.data.searchRuleId", instanceOf(String.class)))
                .andExpect(jsonPath("$.data.feedbackId").value("1"))
                .andExpect(jsonPath("$.data.feedbackId", instanceOf(String.class)))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    @DisplayName("멱등 재생이면 200 으로 기존 후보를 돌려준다")
    void idempotentReplayReturns200() throws Exception {
        given(service.create(any())).willReturn(ParseCandidateOutcome.existing(777L));

        mockMvc.perform(post("/api/v1/review/inquiries/1/scene-exclude-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .header("Idempotency-Key", "rk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.searchRuleId").value("777"))
                .andExpect(jsonPath("$.data.searchRuleId", instanceOf(String.class)));
    }

    @Test
    @DisplayName("빈 Idempotency-Key 는 400 으로 거부한다")
    void blankIdempotencyKeyRejected() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/scene-exclude-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .header("Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("long 범위를 넘는 문자열 targetSceneId 는 500 이 아니라 400 으로 막는다")
    void overflowStringSceneIdRejected() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/scene-exclude-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .header("Idempotency-Key", "rk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetSceneId\":\"99999999999999999999\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("targetSceneId 누락은 400 으로 막는다 (장면 불일치와 구분)")
    void missingSceneIdRejected() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/scene-exclude-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .header("Idempotency-Key", "rk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("편집기자는 제외 후보 생성 경로에 접근할 수 없다")
    void editorForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/scene-exclude-candidate")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .header("Idempotency-Key", "rk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }
}
