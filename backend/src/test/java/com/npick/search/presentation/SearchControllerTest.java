package com.npick.search.presentation;

import java.time.LocalDate;
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
import com.npick.search.application.query.search.ExecuteSearchQuery;
import com.npick.search.application.query.search.ExecuteSearchUseCase;
import com.npick.search.application.query.search.SearchExecutionResult;
import com.npick.search.domain.model.QueryResolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SearchController.class)
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
class SearchControllerTest {

    private static final AuthenticatedMember EDITOR = new AuthenticatedMember(9001L, "editor01", "h", "EDITOR");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ExecuteSearchUseCase useCase;

    @Test
    @DisplayName("계약이 정한 data 모양 그대로 응답한다")
    void respondsWithTheContractShape() throws Exception {
        given(useCase.execute(any())).willReturn(succeeded());

        mockMvc.perform(post("/api/v1/search")
                        .with(user(EDITOR))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"명절 교통\",\"explicit_filters\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search_execution_id").value("700"))
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.degraded_reasons").isEmpty())
                .andExpect(jsonPath("$.data.query_resolution_status").value("resolved"))
                .andExpect(jsonPath("$.data.has_applied_review_rule").value(false))
                .andExpect(
                        jsonPath("$.data.guard_summary.excluded_result_count").value(0))
                .andExpect(jsonPath("$.data.guard_summary.reasons").isEmpty())
                .andExpect(jsonPath("$.data.results[0].search_result_id").value("801"))
                .andExpect(jsonPath("$.data.results[0].scene_id").value("9301"))
                .andExpect(jsonPath("$.data.results[0].clip_id").value("9101"))
                .andExpect(jsonPath("$.data.results[0].rank").value(1))
                .andExpect(jsonPath("$.data.results[0].display_name").value("KBC 뉴스9"))
                .andExpect(jsonPath("$.data.results[0].start_time_ms").value(42000))
                .andExpect(jsonPath("$.data.results[0].broadcast_date.value").value("2026-02-14"))
                .andExpect(jsonPath("$.data.results[0].broadcast_date.verification_status")
                        .value("verified"))
                .andExpect(jsonPath("$.data.results[0].filmed_date.value").doesNotExist())
                .andExpect(jsonPath("$.data.results[0].filmed_date.verification_status")
                        .value("unknown"))
                .andExpect(jsonPath("$.data.results[0].match_evidence[0].field").value("ocr"));
    }

    @Test
    @DisplayName("ID 는 전부 문자열로 나간다")
    void identifiersAreStrings() throws Exception {
        // 계약의 ID 는 문자열이다. TSID 가 2^53 을 넘으면 JS 가 숫자로 받아 값을 잃는다.
        given(useCase.execute(any())).willReturn(succeeded());

        mockMvc.perform(post("/api/v1/search")
                        .with(user(EDITOR))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"명절 교통\",\"explicit_filters\":{}}"))
                .andExpect(jsonPath("$.data.search_execution_id").isString())
                .andExpect(jsonPath("$.data.results[0].search_result_id").isString())
                .andExpect(jsonPath("$.data.results[0].scene_id").isString())
                .andExpect(jsonPath("$.data.results[0].clip_id").isString());
    }

    @Test
    @DisplayName("기록이 저장되지 않았으면 ID 자리를 null 로 내보낸다")
    void unsavedResultsCarryNullIdentifiers() throws Exception {
        // 계약 §5.1: snapshot_save_failed 면 search_execution_id 와 모든 search_result_id 는
        // null 이다. 이 결과로는 문의할 수 없다.
        given(useCase.execute(any())).willReturn(unsaved());

        mockMvc.perform(post("/api/v1/search")
                        .with(user(EDITOR))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"명절 교통\",\"explicit_filters\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("degraded"))
                .andExpect(jsonPath("$.data.degraded_reasons[0]").value("snapshot_save_failed"))
                .andExpect(jsonPath("$.data.search_execution_id").value((Object) null))
                .andExpect(jsonPath("$.data.results[0].search_result_id").value((Object) null));
    }

    @Test
    @DisplayName("날짜 필터를 명시 필터로 옮겨 넘긴다")
    void mapsDateFiltersToTheQuery() throws Exception {
        given(useCase.execute(any())).willReturn(succeeded());

        mockMvc.perform(post("/api/v1/search")
                        .with(user(EDITOR))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"명절 교통",
                                 "explicit_filters":{"broadcast_date":{"from":"2026-09-01","to":"2026-09-03"}}}
                                """))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(ExecuteSearchQuery.class);
        verify(useCase).execute(captor.capture());
        ExecuteSearchQuery query = captor.getValue();
        assertThat(query.rawQuery()).isEqualTo("명절 교통");
        assertThat(query.memberId()).isEqualTo(9001L);
        assertThat(query.explicitFilters().ranges())
                .containsOnlyKeys(QueryResolution.DateField.BROADCAST_DATE)
                .allSatisfy((field, range) -> {
                    assertThat(range.from()).isEqualTo(LocalDate.of(2026, 9, 1));
                    assertThat(range.to()).isEqualTo(LocalDate.of(2026, 9, 3));
                });
    }

    @Test
    @DisplayName("빈 검색어는 400 으로 막는다")
    void rejectsBlankQuery() throws Exception {
        mockMvc.perform(post("/api/v1/search")
                        .with(user(EDITOR))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \",\"explicit_filters\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/search")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"명절 교통\",\"explicit_filters\":{}}"))
                .andExpect(status().isUnauthorized());
    }

    private static SearchExecutionResult succeeded() {
        return new SearchExecutionResult(
                700L,
                List.of(),
                true,
                false,
                SearchExecutionResult.GuardSummary.none(),
                List.of(),
                List.of(card(801L)));
    }

    private static SearchExecutionResult unsaved() {
        return new SearchExecutionResult(
                null,
                List.of("snapshot_save_failed"),
                true,
                false,
                SearchExecutionResult.GuardSummary.none(),
                List.of(),
                List.of(card(null)));
    }

    private static SearchExecutionResult.ResultCard card(Long resultId) {
        return new SearchExecutionResult.ResultCard(
                resultId,
                9301L,
                9101L,
                1,
                "KBC 뉴스9",
                "서울역 귀성 인파",
                42000,
                49000,
                new SearchExecutionResult.DateValue(LocalDate.of(2026, 2, 14), "verified"),
                SearchExecutionResult.DateValue.unknown(),
                "b_roll",
                "역사 인파",
                List.of("서울역"),
                List.of(new SearchExecutionResult.MatchEvidence("ocr", "서울역 · 설 연휴 귀성객", "keyframe_ocr", "verified")));
    }
}
