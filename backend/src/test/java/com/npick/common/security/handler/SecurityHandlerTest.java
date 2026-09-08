package com.npick.common.security.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import tools.jackson.databind.ObjectMapper;

import com.npick.common.error.handler.ApiErrorResponseWriter;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHandlerTest {

    private final ApiErrorResponseWriter writer =
            new ApiErrorResponseWriter(new ObjectMapper(), new ErrorTypeHttpStatusMapper());

    @Test
    @DisplayName("미인증요청은 401과 실패봉투를 기록한다")
    void unauthenticatedRequestReturns401WithFailureEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/review/inquiries");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint(writer).commence(request, response, new AuthenticationException("no auth") {});

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"isSuccess\":false")
                .contains("COMM_401");
    }

    @Test
    @DisplayName("권한부족요청은 403과 실패봉투를 기록한다")
    void forbiddenRequestReturns403WithFailureEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/review/inquiries/1/claim");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAccessDeniedHandler(writer).handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("COMM_403");
    }
}
