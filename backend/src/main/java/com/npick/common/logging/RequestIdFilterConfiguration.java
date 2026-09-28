package com.npick.common.logging;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@link RequestIdFilter} 를 서블릿 필터 체인 최전방에 등록한다 (S15P21A501-136). 보안 필터보다 앞서 돌아야 인증 실패 응답에도 request ID 가 실리고, 처리 전 구간의
 * 로그에 붙는다.
 */
@Configuration
public class RequestIdFilterConfiguration {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
