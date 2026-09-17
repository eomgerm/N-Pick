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
import com.npick.search.application.CreateParsePatchCandidateService;
import com.npick.search.application.ParseCandidateOutcome;

import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ParsePatchCandidateController.class)
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
class ParsePatchCandidateControllerTest {

    private static final String BODY = """
            {"condition":{"syntax_version":"parse-rule/v1","resolution_schema_version":"query-resolver/v2",
              "all":[{"axis":"locations","op":"has_value","value":"○○공장"}]},
             "patch":{"syntax_version":"parse-rule/v1",
              "operations":[{"op":"remove_item","axis":"locations","type":"location","value":"○○공장"}]}}
            """;

    private static final AuthenticatedMember REVIEWER = new AuthenticatedMember(9L, "reviewer01", "h", "REVIEWER");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CreateParsePatchCandidateService service;

    @Test
    @DisplayName("검수자가 새 후보를 만들면 201 과 생성 id 를 준다")
    void reviewerCreatesCandidate() throws Exception {
        given(service.create(any())).willReturn(ParseCandidateOutcome.created(777L));

        mockMvc.perform(post("/api/v1/review/inquiries/1/parse-patch-candidate")
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

        mockMvc.perform(post("/api/v1/review/inquiries/1/parse-patch-candidate")
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
        mockMvc.perform(post("/api/v1/review/inquiries/1/parse-patch-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .header("Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("replacesRuleId 가 소수면 400 으로 거부한다 (asLong 이 소수부를 삼키지 않는다)")
    void fractionalReplacesRuleIdRejected() throws Exception {
        String body =
                "{\"condition\":{\"syntax_version\":\"parse-rule/v1\",\"resolution_schema_version\":\"query-resolver/v2\","
                        + "\"all\":[{\"axis\":\"locations\",\"op\":\"has_value\",\"value\":\"공장\"}]},"
                        + "\"patch\":{\"syntax_version\":\"parse-rule/v1\",\"operations\":"
                        + "[{\"op\":\"remove_item\",\"axis\":\"locations\",\"type\":\"location\",\"value\":\"공장\"}]},"
                        + "\"replacesRuleId\":1.9}";

        mockMvc.perform(post("/api/v1/review/inquiries/1/parse-patch-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .header("Idempotency-Key", "rk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("편집기자는 후보 생성 경로에 접근할 수 없다")
    void editorForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/parse-patch-candidate")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .header("Idempotency-Key", "rk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }
}
