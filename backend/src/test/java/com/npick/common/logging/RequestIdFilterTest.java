package com.npick.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void 헤더가_없으면_request_id_를_생성해_응답_헤더에_에코한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noopChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void 들어온_request_id_가_있으면_그대로_사용하고_에코한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "req-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noopChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req-abc-123");
    }

    @Test
    void 공백_request_id_는_무시하고_새로_생성한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noopChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    void 체인_동안_MDC_에_request_id_가_있고_끝나면_지워진다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "req-xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] duringChain = new String[1];
        FilterChain capturing = (req, res) -> duringChain[0] = MDC.get(RequestIdFilter.MDC_KEY);

        filter.doFilter(request, response, capturing);

        assertThat(duringChain[0]).isEqualTo("req-xyz");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private static FilterChain noopChain() {
        return (req, res) -> {};
    }
}
