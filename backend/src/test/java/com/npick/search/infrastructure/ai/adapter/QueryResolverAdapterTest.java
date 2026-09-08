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

import com.npick.search.application.error.SearchErrorCode;
import com.npick.search.application.error.SearchException;
import com.npick.search.application.port.QueryResolution;
import com.npick.search.application.port.QueryResolutionRequest;
import com.npick.search.infrastructure.ai.client.QueryResolverClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** mock resolver 로 정상·timeout·schema 오류 세 경로를 확인한다 (S15P21A501-45 완료 조건). */
class QueryResolverAdapterTest {

    private static final String BASE_URL = "http://query-resolver.test";

    private static final String VALID_RESOLUTION = """
            {
              "schema_version": "1.0",
              "intent": "scene_search",
              "date_windows": [
                {
                  "field": "broadcast_date",
                  "start": "2024-03-01",
                  "end_exclusive": "2024-04-01",
                  "origin": "explicit_query",
                  "query_span": {"start": 0, "end": 7},
                  "confidence": 0.9
                }
              ],
              "incident_names": [],
              "entities": [
                {
                  "type": "person",
                  "value": "홍길동",
                  "origin": "inferred",
                  "query_span": null,
                  "confidence": 0.4
                }
              ],
              "locations": [],
              "expanded_terms": ["집중호우"],
              "confidence": 0.8
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
    @DisplayName("정상 응답을 resolution 으로 파싱하고 canonical scope 를 그대로 보낸다")
    void resolvesValidResponse() {
        server.expect(requestTo(BASE_URL + "/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.canonical_query").value("2024년 3월 집중호우"))
                .andRespond(withSuccess(VALID_RESOLUTION, MediaType.APPLICATION_JSON));

        QueryResolution resolution = adapter().resolve(QueryResolutionRequest.of("2024년 3월 집중호우"));

        assertThat(resolution.schemaVersion()).isEqualTo("1.0");
        assertThat(resolution.intent()).isEqualTo(QueryResolution.Intent.SCENE_SEARCH);
        assertThat(resolution.confidence()).isEqualTo(0.8);
        assertThat(resolution.expandedTerms()).containsExactly("집중호우");

        QueryResolution.DateWindow window = resolution.dateWindows().getFirst();
        assertThat(window.field()).isEqualTo(QueryResolution.DateField.BROADCAST_DATE);
        assertThat(window.start()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(window.endExclusive()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(window.origin()).isEqualTo(QueryResolution.Origin.EXPLICIT_QUERY);
        assertThat(window.querySpan()).isEqualTo(new QueryResolution.QuerySpan(0, 7));

        QueryResolution.Entity entity = resolution.entities().getFirst();
        assertThat(entity.type()).isEqualTo(QueryResolution.EntityType.PERSON);
        assertThat(entity.origin()).isEqualTo(QueryResolution.Origin.INFERRED);
        assertThat(entity.querySpan()).isNull();

        server.verify();
    }

    @Test
    @DisplayName("timeout 이면 재시도 없이 RESOLVER_TIMEOUT 으로 실패한다")
    void failsFastOnTimeout() {
        server.expect(requestTo(BASE_URL + "/resolve")).andRespond(request -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"));
        });

        assertThatThrownBy(() -> adapter().resolve(QueryResolutionRequest.of("어제 뉴스")))
                .isInstanceOf(SearchException.class)
                .extracting(ex -> ((SearchException) ex).errorCode())
                .isEqualTo(SearchErrorCode.RESOLVER_TIMEOUT);

        // 기대한 호출이 정확히 1회만 일어났는지 확인한다. 재시도가 있으면 여기서 깨진다.
        server.verify();
    }

    @Test
    @DisplayName("알 수 없는 intent 값은 RESOLVER_SCHEMA_INVALID 로 실패한다")
    void failsOnUnknownEnumValue() {
        server.expect(requestTo(BASE_URL + "/resolve"))
                .andRespond(withSuccess(
                        VALID_RESOLUTION.replace("\"scene_search\"", "\"time_travel\""), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter().resolve(QueryResolutionRequest.of("어제 뉴스")))
                .isInstanceOf(SearchException.class)
                .extracting(ex -> ((SearchException) ex).errorCode())
                .isEqualTo(SearchErrorCode.RESOLVER_SCHEMA_INVALID);
    }

    @Test
    @DisplayName("JSON 이 깨진 응답은 RESOLVER_SCHEMA_INVALID 로 실패한다")
    void failsOnMalformedJson() {
        server.expect(requestTo(BASE_URL + "/resolve"))
                .andRespond(withSuccess("{\"intent\": ", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter().resolve(QueryResolutionRequest.of("어제 뉴스")))
                .isInstanceOf(SearchException.class)
                .extracting(ex -> ((SearchException) ex).errorCode())
                .isEqualTo(SearchErrorCode.RESOLVER_SCHEMA_INVALID);
    }

    private QueryResolverAdapter adapter() {
        QueryResolverClient client = HttpServiceProxyFactory.builderFor(
                        RestClientAdapter.create(restClientBuilder.build()))
                .build()
                .createClient(QueryResolverClient.class);
        return new QueryResolverAdapter(client);
    }
}
