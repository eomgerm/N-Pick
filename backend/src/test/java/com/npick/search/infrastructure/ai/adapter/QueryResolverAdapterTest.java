package com.npick.search.infrastructure.ai.adapter;

import java.net.SocketTimeoutException;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** mock resolver 로 정상·timeout·schema 오류 경로를 확인한다 (S15P21A501-45 완료 조건). */
class QueryResolverAdapterTest {

    private static final String BASE_URL = "http://query-resolver.test";

    private static final String VALID_RESPONSE = """
            {
              "normalization": {
                "normalized_query": "부산 여름 작년 침수",
                "search_tokens": ["작년", "여름", "부산", "침수"],
                "normalization_version": "query-norm/v1:1a2b3c4d:kiwi0.23.2:model0.23.0"
              },
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
              "model_version": "qwen2.5:7b",
              "error": null
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
        server.expect(requestTo(BASE_URL + "/query/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.query").value("작년 여름에 부산 침수됐던 장면 좀 찾아줘"))
                .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON));

        adapter().resolve("작년 여름에 부산 침수됐던 장면 좀 찾아줘");

        server.verify();
    }

    @Test
    @DisplayName("정상 응답을 resolution 과 세 가지 버전으로 파싱한다")
    void resolvesValidResponse() {
        server.expect(requestTo(BASE_URL + "/query/resolve"))
                .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON));

        QueryResolutionResult result = adapter().resolve("2024년 3월 집중호우");

        assertThat(result.isResolved()).isTrue();
        assertThat(result.failure()).isNull();
        assertThat(result.normalization().searchTokens()).containsExactly("작년", "여름", "부산", "침수");
        assertThat(result.normalization().normalizationVersion())
                .isEqualTo("query-norm/v1:1a2b3c4d:kiwi0.23.2:model0.23.0");
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
        server.expect(requestTo(BASE_URL + "/query/resolve")).andRespond(request -> {
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
    @DisplayName("해석만 못 읽으면 정규화는 살려서 SCHEMA_INVALID 로 돌려준다")
    void keepsNormalizationWhenResolutionMappingFails() {
        // 날짜 형식이 어긋난 경우다. 리졸버 validator 는 date.fromisoformat 로 "20240301" 을
        // 통과시키지만 Jackson 의 LocalDate 는 YYYY-MM-DD 만 받는다. 이때 정규화까지 버리면
        // BM25 fallback 재료가 사라진다 (FRD v3.1 §6.2).
        respondWith(VALID_RESPONSE.replace("\"2024-03-01\"", "\"20240301\""));

        QueryResolutionResult result = adapter().resolve("2024년 3월");

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
        assertThat(result.normalization().searchTokens()).containsExactly("작년", "여름", "부산", "침수");
    }

    @Test
    @DisplayName("알 수 없는 enum 값도 정규화를 살린다")
    void keepsNormalizationOnUnknownEnumValue() {
        respondWith(VALID_RESPONSE.replace("\"scene_search\"", "\"time_travel\""));

        QueryResolutionResult result = adapter().resolve("어제 뉴스");

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
        assertThat(result.normalization().searchTokens()).isNotEmpty();
    }

    @Test
    @DisplayName("resolution 과 error 가 동시에 오면 정규화만 살리고 실패로 본다")
    void rejectsBothResolutionAndError() {
        respondWith(VALID_RESPONSE.replace("\"error\": null", "\"error\": {\"category\": \"RESOLVER_TIMEOUT\"}"));

        QueryResolutionResult result = adapter().resolve("어제 뉴스");

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
    }

    @Test
    @DisplayName("JSON 이 깨진 응답은 RESOLVER_SCHEMA_INVALID 로 실패한다")
    void failsOnMalformedJson() {
        respondWith("{\"resolution\": ");

        assertThatSchemaInvalid();
    }

    @Test
    @DisplayName("정규화조차 없는 응답은 예외다 — 검색을 이어갈 재료가 없다")
    void failsWhenNormalizationMissing() {
        // 엔드포인트 계약이 통째로 어긋난 경우다. search_tokens 가 없으면 BM25 fallback 도
        // 만들 수 없으므로 degraded 가 아니라 실패다.
        respondWith("{\"parsed\": {\"intent\": \"scene_search\"}}");

        assertThatSchemaInvalid();
    }

    @Test
    @DisplayName("origin 이 빠진 항목은 명시와 추정을 구분할 수 없어 해석 실패로 본다")
    void failsOnMissingOrigin() {
        respondWith(VALID_RESPONSE.replace("\"origin\": \"inferred\",", ""));

        QueryResolutionResult result = adapter().resolve("어제 뉴스");

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
        assertThat(result.normalization().searchTokens()).isNotEmpty();
    }

    @Test
    @DisplayName("해석이 실패해도 정규화 결과는 살려서 돌려준다")
    void keepsNormalizationWhenResolverFails() {
        // 이 토큰이 없으면 원 검색어 BM25 fallback 을 만들 수 없다 (FRD v3.1 §6.2).
        respondWith("""
                {
                  "normalization": {
                    "normalized_query": "부산 여름 작년 침수",
                    "search_tokens": ["작년", "여름", "부산", "침수"],
                    "normalization_version": "query-norm/v1:1a2b3c4d:kiwi0.23.2:model0.23.0"
                  },
                  "resolution": null,
                  "error": {"category": "RESOLVER_TIMEOUT", "message": "timed out"}
                }
                """);

        QueryResolutionResult result = adapter().resolve("작년 여름 부산 침수");

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryResolverErrorCode.RESOLVER_TIMEOUT);
        assertThat(result.normalization().searchTokens()).containsExactly("작년", "여름", "부산", "침수");
    }

    @Test
    @DisplayName("모르는 실패 사유는 RESOLVER_FAILED 로 접되 실패라는 사실은 유지한다")
    void unknownFailureCategoryStillFails() {
        respondWith("""
                {
                  "normalization": {
                    "normalized_query": "뉴스",
                    "search_tokens": ["뉴스"],
                    "normalization_version": "query-norm/v1:1a2b3c4d"
                  },
                  "resolution": null,
                  "error": {"category": "PARALLEL_UNIVERSE", "message": "?"}
                }
                """);

        QueryResolutionResult result = adapter().resolve("뉴스");

        assertThat(result.isResolved()).isFalse();
        assertThat(result.failure()).isEqualTo(QueryResolverErrorCode.RESOLVER_FAILED);
    }

    @Test
    @DisplayName("리졸버가 400 이면 의존성 장애가 아니라 QUERY_NOT_NORMALIZABLE 이다")
    void mapsBadRequestToQueryNotNormalizable() {
        server.expect(requestTo(BASE_URL + "/query/resolve"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"detail\":\"정규화 불가\"}"));

        assertThatThrownBy(() -> adapter().resolve("!!!"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(QueryResolverErrorCode.QUERY_NOT_NORMALIZABLE);
    }

    @Test
    @DisplayName("429 는 RESOLVER_RATE_LIMITED 로 구분한다")
    void mapsTooManyRequestsToRateLimited() {
        server.expect(requestTo(BASE_URL + "/query/resolve")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> adapter().resolve("어제 뉴스"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(QueryResolverErrorCode.RESOLVER_RATE_LIMITED);
    }

    @Test
    @DisplayName("4xx 중 배선 오류(422)는 사용자 입력 문제와 섞지 않는다")
    void mapsOtherClientErrorsToResolverFailed() {
        server.expect(requestTo(BASE_URL + "/query/resolve")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> adapter().resolve("어제 뉴스"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(QueryResolverErrorCode.RESOLVER_FAILED);
    }

    @Test
    @DisplayName("resolution 과 error 가 둘 다 없어도 정규화는 살려서 돌려준다")
    void keepsNormalizationWhenBothResolutionAndErrorAreMissing() {
        respondWith("""
                {"normalization": {"normalized_query": "어제 뉴스",
                 "search_tokens": ["어제", "뉴스"], "normalization_version": "kiwi:v1"}}
                """);

        var result = adapter().resolve("어제 뉴스");

        assertThat(result.normalization().searchTokens()).containsExactly("어제", "뉴스");
        assertThat(result.failure()).isEqualTo(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
    }

    @Test
    @DisplayName("해석 배열 키가 개명되면 조용히 빈 해석으로 성공하지 않는다")
    void treatsMissingResolutionArraysAsSchemaInvalid() {
        respondWith("""
                {"normalization": {"normalized_query": "어제 뉴스",
                 "search_tokens": ["어제", "뉴스"], "normalization_version": "kiwi:v1"},
                 "resolution": {"schema_version": "query-resolution/v1", "intent": "scene_search",
                  "entity_list": [], "confidence": 0.8},
                 "resolution_schema_version": "query-resolution/v1",
                 "prompt_version": "p", "model_version": "m"}
                """);

        var result = adapter().resolve("어제 뉴스");

        assertThat(result.resolution()).isNull();
        assertThat(result.failure()).isEqualTo(QueryResolverErrorCode.RESOLVER_SCHEMA_INVALID);
        assertThat(result.normalization().searchTokens()).containsExactly("어제", "뉴스");
    }

    private void respondWith(String body) {
        server.expect(requestTo(BASE_URL + "/query/resolve")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
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
