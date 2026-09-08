package com.npick.search.infrastructure.ai.adapter;

import java.net.SocketTimeoutException;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.QueryResolverErrorCode;
import com.npick.search.application.port.QueryResolution;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.infrastructure.ai.client.QueryResolverClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** mock resolver 로 정상·timeout·schema 오류 경로를 확인한다 (S15P21A501-45 완료 조건). */
class QueryResolverAdapterTest {

    private static final String BASE_URL = "http://query-resolver.test";

    private static final String VALID_RESPONSE = """
            {
              "resolution": {
                "schema_version": "query-resolver/v2",
                "intent": "scene_search",
                "date_windows": [
                  {
                    "field": "filmed_date",
                    "start": "2024-03-01",
                    "end_exclusive": "2024-04-01",
                    "origin": "explicit_query",
                    "query_span": {"start": 0, "end": 7},
                    "confidence": 0.9
                  }
                ],
                "incident_names": [],
                "entities": [
                  {"type": "person", "value": "홍길동", "origin": "inferred",
                   "query_span": null, "confidence": 0.4}
                ],
                "locations": [],
                "classifications": [
                  {"type": "weather", "value": "비 오는", "origin": "explicit_query",
                   "query_span": {"start": 8, "end": 12}, "confidence": 0.7}
                ],
                "expanded_terms": ["집중호우"],
                "confidence": 0.8
              },
              "findings": [
                {"path": "entities[0]", "action": "demoted_to_inferred",
                 "reason": "원문에 없는 값을 explicit_query 로 주장했다"}
              ],
              "resolution_schema_version": "query-resolver/v2",
              "prompt_version": "query-resolver-prompt/v1:1a2b3c4d",
              "model_version": "qwen2.5:7b"
            }
            """;

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    @Test
    @DisplayName("정규화 질의가 아니라 사용자 원문을 그대로 보낸다")
    void sendsRawQuery() {
        server.expect(requestTo(BASE_URL + "/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query").value("작년 여름에 부산 침수됐던 장면 좀 찾아줘"))
                .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON));

        adapter().resolve("작년 여름에 부산 침수됐던 장면 좀 찾아줘");

        server.verify();
    }

    @Test
    @DisplayName("정상 응답을 resolution 과 세 가지 버전으로 파싱한다")
    void resolvesValidResponse() {
        server.expect(requestTo(BASE_URL + "/resolve"))
                .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON));

        QueryResolutionResult result = adapter().resolve("2024년 3월 집중호우");

        assertThat(result.resolutionSchemaVersion()).isEqualTo("query-resolver/v2");
        assertThat(result.promptVersion()).isEqualTo("query-resolver-prompt/v1:1a2b3c4d");
        assertThat(result.modelVersion()).isEqualTo("qwen2.5:7b");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.path()).isEqualTo("entities[0]");
            assertThat(finding.action()).isEqualTo("demoted_to_inferred");
        });

        QueryResolution resolution = result.resolution();
        assertThat(resolution.intent()).isEqualTo(QueryResolution.Intent.SCENE_SEARCH);
        assertThat(resolution.confidence()).isEqualTo(0.8);
        assertThat(resolution.expandedTerms()).containsExactly("집중호우");

        QueryResolution.DateWindow window = resolution.dateWindows().getFirst();
        assertThat(window.field()).isEqualTo(QueryResolution.DateField.FILMED_DATE);
        assertThat(window.start()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(window.endExclusive()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(window.origin()).isEqualTo(QueryResolution.Origin.EXPLICIT_QUERY);
        assertThat(window.querySpan()).isEqualTo(new QueryResolution.QuerySpan(0, 7));

        QueryResolution.Entity entity = resolution.entities().getFirst();
        assertThat(entity.type()).isEqualTo(QueryResolution.EntityType.PERSON);
        assertThat(entity.origin()).isEqualTo(QueryResolution.Origin.INFERRED);
        assertThat(entity.querySpan()).isNull();

        QueryResolution.Classification classification =
                resolution.classifications().getFirst();
        assertThat(classification.type()).isEqualTo(QueryResolution.ClassificationType.WEATHER);
        assertThat(classification.value()).isEqualTo("비 오는");
    }

    @Test
    @DisplayName("timeout 이면 재시도 없이 RESOLVER_TIMEOUT 으로 실패한다")
    void failsFastOnTimeout() {
        server.expect(requestTo(BASE_URL + "/resolve")).andRespond(request -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"));
        });

        assertThatThrownBy(() -> adapter().resolve("어제 뉴스"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(QueryResolverErrorCode.RESOLVER_TIMEOUT);

        // 기대한 호출이 정확히 1회만 일어났는지 확인한다. 재시도가 있으면 여기서 깨진다.
        server.verify();
    }

    @Test
    @DisplayName("알 수 없는 enum 값은 RESOLVER_SCHEMA_INVALID 로 실패한다")
    void failsOnUnknownEnumValue() {
        respondWith(VALID_RESPONSE.replace("\"scene_search\"", "\"time_travel\""));

        assertThatSchemaInvalid();
    }

    @Test
    @DisplayName("JSON 이 깨진 응답은 RESOLVER_SCHEMA_INVALID 로 실패한다")
    void failsOnMalformedJson() {
        respondWith("{\"resolution\": ");

        assertThatSchemaInvalid();
    }

    @Test
    @DisplayName("키 이름이 어긋나 빈 응답이 되면 성공으로 통과시키지 않는다")
    void failsOnSilentlyEmptyResponse() {
        // 엔드포인트 계약이 어긋나 우리가 아는 키가 하나도 없는 경우다. 기본값으로 채워
        // 조건 없는 해석을 성공이라고 넘기면 검색이 조용히 잘못된다.
        respondWith("{\"parsed\": {\"intent\": \"scene_search\"}}");

        assertThatSchemaInvalid();
    }

    @Test
    @DisplayName("origin 이 빠진 항목은 명시와 추정을 구분할 수 없어 실패로 본다")
    void failsOnMissingOrigin() {
        respondWith(VALID_RESPONSE.replace("\"origin\": \"inferred\",", ""));

        assertThatSchemaInvalid();
    }

    private void respondWith(String body) {
        server.expect(requestTo(BASE_URL + "/resolve")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void assertThatSchemaInvalid() {
        assertThatThrownBy(() -> adapter().resolve("어제 뉴스"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
    }

    private QueryResolverAdapter adapter() {
        QueryResolverClient client = HttpServiceProxyFactory.builderFor(
                        RestClientAdapter.create(restClientBuilder.build()))
                .build()
                .createClient(QueryResolverClient.class);
        return new QueryResolverAdapter(client);
    }
}
