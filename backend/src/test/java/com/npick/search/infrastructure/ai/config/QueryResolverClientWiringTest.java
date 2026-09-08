package com.npick.search.infrastructure.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.npick.search.infrastructure.ai.client.QueryResolverClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @ImportHttpServices} 배선과 <b>설정 그룹 이름</b>을 검증한다.
 *
 * <p>그룹 이름은 코드({@link QueryResolverClientConfig#QUERY_RESOLVER_GROUP})와 설정
 * ({@code spring.http.serviceclient.<group>.base-url})이 문자열로만 연결돼 있다. 오타가 나도 컨텍스트는 정상적으로 뜨고, 실패는 첫 호출 때 상대 URI 오류로만
 * 드러난다 — 기동 검증으로는 절대 안 잡힌다.
 *
 * <p>전체 컨텍스트를 띄우는 테스트는 DB 환경 변수가 있을 때만 실행되므로, 이 슬라이스가 CI 에서 그 공백을 메운다.
 */
class QueryResolverClientWiringTest {

    private static final String BASE_URL_KEY =
            "spring.http.serviceclient." + QueryResolverClientConfig.QUERY_RESOLVER_GROUP + ".base-url";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class,
                    HttpServiceClientPropertiesAutoConfiguration.class,
                    HttpServiceClientAutoConfiguration.class))
            .withUserConfiguration(QueryResolverClientConfig.class);

    @Test
    @DisplayName("설정한 그룹 이름으로 base-url 이 붙어 클라이언트 빈이 만들어진다")
    void wiresClientWithConfiguredBaseUrl() {
        runner.withPropertyValues(BASE_URL_KEY + "=http://query-resolver.test")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(QueryResolverClient.class));
    }

    @Test
    @DisplayName("설정 키가 참조하는 그룹 이름은 queryResolver 다")
    void groupNameMatchesConfigurationKey() {
        // application.yml 의 `spring.http.serviceclient.queryResolver` 와 같아야 한다.
        // 한쪽만 바꾸면 조용히 미설정 상태가 된다.
        assertThat(QueryResolverClientConfig.QUERY_RESOLVER_GROUP).isEqualTo("queryResolver");
    }
}
