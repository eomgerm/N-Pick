package com.npick.search.infrastructure.ai.config;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

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
    @DisplayName("코드의 그룹 이름이 application.yml 의 키와 실제로 같다")
    void groupNameMatchesConfigurationKey() throws IOException {
        // 상수를 리터럴과만 비교하면 정작 yml 쪽 오타를 못 잡는다 — 양쪽이 따로 놀아도
        // 테스트는 통과하고 실패는 첫 호출 때 상대 URI 오류로만 드러난다. 그래서 실제 파일을 읽는다.
        var defaults = new YamlPropertySourceLoader()
                .load("resolver-defaults", new FileSystemResource("src/main/resources/application.yml"))
                .getFirst();

        assertThat(defaults.getProperty(BASE_URL_KEY))
                .as("application.yml 에 %s 가 있어야 한다", BASE_URL_KEY)
                .isNotNull();
    }
}
