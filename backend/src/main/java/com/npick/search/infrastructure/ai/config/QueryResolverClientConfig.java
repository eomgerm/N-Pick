package com.npick.search.infrastructure.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

import com.npick.search.infrastructure.ai.client.QueryResolverClient;
import com.npick.search.infrastructure.ai.client.QueryTokenizerClient;

/**
 * 질의 리졸버 HTTP 클라이언트를 빈으로 등록한다.
 *
 * <p>group 이름이 {@code spring.http.serviceclient.<group>} 설정 키와 연결된다. 접속 대상과 timeout 을 코드가 아니라 설정으로 정하는 지점이다.
 */
@Configuration
@ImportHttpServices(
        group = QueryResolverClientConfig.QUERY_RESOLVER_GROUP,
        types = {QueryResolverClient.class, QueryTokenizerClient.class},
        clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class QueryResolverClientConfig {

    public static final String QUERY_RESOLVER_GROUP = "queryResolver";
}
