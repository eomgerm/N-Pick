package com.npick.tag.presentation;

import java.util.List;

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
import com.npick.tag.application.CreateTagCorrectionCandidateService;
import com.npick.tag.application.DiscardOneTagCorrectionCandidateUseCase;
import com.npick.tag.application.DiscardTagCorrectionCandidateUseCase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TagCorrectionCandidateController.class)
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
class TagCorrectionCandidateControllerTest {

    private static final String REPLACE_BODY = """
            {"operations":[
              {"action":"REJECT","scope":"SCENE","tagType":"location","matchValue":"서울","displayName":"서울"},
              {"action":"APPROVE","scope":"SCENE","tagType":"location","matchValue":"제주도","displayName":"제주도"}]}
            """;

    private static final AuthenticatedMember REVIEWER = new AuthenticatedMember(9L, "reviewer01", "h", "REVIEWER");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CreateTagCorrectionCandidateService service;

    @MockitoBean
    DiscardTagCorrectionCandidateUseCase discardService;

    @MockitoBean
    DiscardOneTagCorrectionCandidateUseCase discardOneService;

    @Test
    @DisplayName("검수자가 교정 후보를 만들면 201 과 생성된 근거 수·id 를 준다")
    void reviewerCreatesCandidate() throws Exception {
        given(service.create(any())).willReturn(List.of(5001L, 5002L));

        mockMvc.perform(post("/api/v1/review/inquiries/1/tag-correction-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REPLACE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.evidenceIds.length()").value(2));
    }

    @Test
    @DisplayName("변경안 요소 필드가 누락되면 서비스에 닿기 전에 400 으로 막는다")
    void rejectsInvalidOperationElement() throws Exception {
        // action 누락 — @Valid 캐스케이드가 없으면 서비스에서 NPE→500 이 되던 자리
        String missingAction =
                "{\"operations\":[{\"scope\":\"SCENE\",\"tagType\":\"location\",\"matchValue\":\"x\",\"displayName\":\"x\"}]}";

        mockMvc.perform(post("/api/v1/review/inquiries/1/tag-correction-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingAction))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("변경안이 비어 있으면 400 으로 거부한다")
    void emptyOperationsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/tag-correction-candidate")
                        .with(user(REVIEWER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("편집기자는 태그 교정 후보 생성 경로에 접근할 수 없다")
    void editorForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/review/inquiries/1/tag-correction-candidate")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REPLACE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("검수자가 대기 후보 하나를 취소하면 문자열 TSID 근거 id 로 서비스를 부르고 본문 없는 200 을 준다")
    void reviewerDiscardsOneCandidate() throws Exception {
        mockMvc.perform(delete("/api/v1/review/inquiries/1/tag-correction-candidate/460000000000000001")
                        .with(user(REVIEWER))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(discardOneService).discardOne(1L, 460000000000000001L, 9L, true);
    }

    @Test
    @DisplayName("편집기자는 태그 교정 후보 개별 취소 경로에 접근할 수 없다")
    void editorForbiddenOnDiscardOne() throws Exception {
        mockMvc.perform(delete("/api/v1/review/inquiries/1/tag-correction-candidate/5001")
                        .with(user(new AuthenticatedMember(20L, "editor01", "h", "EDITOR")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
