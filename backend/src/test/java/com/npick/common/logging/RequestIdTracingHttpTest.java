package com.npick.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.support.NpickPostgres;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// request ID 가 필터→MDC→오류 응답(헤더·envelope)까지 실제로 흐르는지 전 구간 확인 (S15P21A501-136).
// 인증 없이 보호 엔드포인트를 치면 401 오류 envelope 가 온다.
@SpringBootTest
@AutoConfigureMockMvc
class RequestIdTracingHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @DisplayName("헤더 없이 오면 request ID 를 생성해 응답 헤더와 오류 envelope 에 싣는다")
    void generatesAndCarriesRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("들어온 request ID 를 응답 헤더와 오류 envelope 에 그대로 에코한다")
    void echoesIncomingRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries").header("X-Request-Id", "trace-42"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "trace-42"))
                .andExpect(jsonPath("$.requestId").value("trace-42"));
    }

    // Origin 은 설정된 허용 출처(application.yml cors.allowed-origins 기본값)와 일치해야 CORS 헤더가 실린다.
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    @Test
    @DisplayName("허용된 출처는 X-Request-Id 를 포함한 요청을 보낼 수 있다")
    void allowsRequestIdPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/inquiries")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Request-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Request-Id")));
    }

    @Test
    @DisplayName("허용된 출처에서 오류 응답의 요청 ID 헤더를 읽을 수 있다")
    void exposesRequestIdOnErrorResponse() throws Exception {
        mockMvc.perform(get("/api/v1/inquiries")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Request-Id", "trace-cors-42"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Request-Id")))
                .andExpect(header().string("X-Request-Id", "trace-cors-42"))
                .andExpect(jsonPath("$.requestId").value("trace-cors-42"));
    }
}
