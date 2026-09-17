package com.npick.common.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.npick.common.error.CommonErrorCode;
import com.npick.common.logging.RequestIdFilter;

class ApiResponseTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 실패_응답은_MDC_의_request_id_를_담는다() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-99");

        ApiResponse<Void> response = ApiResponse.failure(CommonErrorCode.VALIDATION_FAILED, "GET /x");

        assertThat(response.requestId()).isEqualTo("req-99");
    }

    @Test
    void request_id_가_없으면_실패_응답의_request_id_는_null_이다() {
        ApiResponse<Void> response = ApiResponse.failure(CommonErrorCode.VALIDATION_FAILED, "GET /x");

        assertThat(response.requestId()).isNull();
    }

    @Test
    void 성공_응답은_request_id_를_담지_않는다() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-99");

        ApiResponse<String> response = ApiResponse.success("ok");

        assertThat(response.requestId()).isNull();
    }
}
