package com.npick.search.infrastructure.ai.adapter;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.npick.search.infrastructure.ai.client.QueryTokenizerClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 확장어 토큰화 어댑터. <b>어떤 실패도 검색을 끊지 않는다</b> (S15P21A501-48 계약 9).
 *
 * <p>계약 불변식(resolver-api §2.4)을 어댑터 경계에서 확인한다 — 잘못된 응답을 정상으로 받으면 색인과 다른 규칙으로 만든 토큰이 검색에 섞인다.
 */
class QueryTokenizerAdapterTest {

    private static final String BASE_URL = "http://query-resolver.test";
    private static final String VERSION = "query-norm/v1:1a2b3c4d";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("확장어 항목별 묶음을 그대로 보존한다")
    void keepsTokensGroupedPerTerm() {
        // S15P21A501-302: 펼치면 후보 조회의 term_set 이 확장어를 OR 로 받아 「중국 음식」이
        // 중국 OR 음식 이 된다. 구를 살리려면 항목 경계가 여기서 살아 있어야 한다.
        respondWith("""
                {"tokens": [["중국","음식"], ["불"]], "normalization_version": "%s"}
                """.formatted(VERSION));

        assertThat(adapter().tokenize(List.of("중국 음식", "불"), VERSION))
                .containsExactly(List.of("중국", "음식"), List.of("불"));
    }

    @Test
    @DisplayName("같은 묶음과 묶음 안의 중복 토큰은 뺀다")
    void dropsDuplicatePhrasesAndTokens() {
        // 한 구가 여러 절에서 가산되면 F-05 의 "같은 개체를 중복 계산하지 않는다" 를 깬다.
        respondWith("""
                {"tokens": [["집중","호우","집중"], ["집중","호우"], ["호우"]], "normalization_version": "%s"}
                """.formatted(VERSION));

        assertThat(adapter().tokenize(List.of("집중호우", "집중 호우", "호우"), VERSION))
                .containsExactly(List.of("집중", "호우"), List.of("호우"));
    }

    @Test
    @DisplayName("빈 토큰이 든 구는 통째로 뺀다")
    void dropsPhraseWithBlankToken() {
        // 토큰만 빼고 남은 것으로 must 를 걸면 구가 헐거워져 「중국 음식」이 중국 단독 매칭으로 되돌아간다.
        // 어댑터가 아니라 여기서 거르는 이유는 근거 설명이다 — 버려진 구의 토큰이 흘러가면
        // matched_keywords 에 origin=expanded 로 떠서 기여하지 않은 확장어 칩이 보인다 (S15P21A501-234).
        respondWith("""
                {"tokens": [["중국"," "], ["면","요리"]], "normalization_version": "%s"}
                """.formatted(VERSION));

        assertThat(adapter().tokenize(List.of("중국 음식", "면 요리"), VERSION)).containsExactly(List.of("면", "요리"));
    }

    @Test
    @DisplayName("공백이 든 토큰이 있는 구도 통째로 뺀다")
    void dropsPhraseWithWhitespaceInToken() {
        // 공백이 있으면 DB 에서 두 토큰으로 쪼개져 구의 의미가 조용히 달라진다.
        respondWith("""
                {"tokens": [["중국 음식"], ["면","요리"]], "normalization_version": "%s"}
                """.formatted(VERSION));

        assertThat(adapter().tokenize(List.of("중국 음식", "면 요리"), VERSION)).containsExactly(List.of("면", "요리"));
    }

    @Test
    @DisplayName("토큰이 0개인 항목은 오류가 아니다")
    void emptyTokensAreNotAnError() {
        // resolver-api §2.3: 기호뿐인 확장어 등은 빈 배열이고 요청 전체를 깨뜨리지 않는다.
        respondWith("""
                {"tokens": [["집중"], []], "normalization_version": "%s"}
                """.formatted(VERSION));

        assertThat(adapter().tokenize(List.of("집중호우", "!!!"), VERSION)).containsExactly(List.of("집중"));
    }

    @Test
    @DisplayName("정규화 버전이 다르면 그 토큰을 쓰지 않는다")
    void rejectsTokensFromAnotherNormalizationVersion() {
        // §2.4-1: 두 값이 다르면 그 토큰과 그 검색은 서로 다른 규칙으로 만들어진 것이다. 섞으면
        // 색인이 하지 않는 경계로 질의해 조용히 0건이 된다.
        respondWith("""
                {"tokens": [["집중"]], "normalization_version": "query-norm/v0:다른버전"}
                """);

        assertThat(adapter().tokenize(List.of("집중호우"), VERSION)).isEmpty();
    }

    @Test
    @DisplayName("항목 수가 안 맞으면 그 응답을 쓰지 않는다")
    void rejectsMismatchedTokenCount() {
        // §2.4-4: len(tokens) == len(texts) 이고 순서가 보존된다. 어긋나면 어느 확장어의 토큰인지
        // 알 수 없다.
        respondWith("""
                {"tokens": [["집중"]], "normalization_version": "%s"}
                """.formatted(VERSION));

        assertThat(adapter().tokenize(List.of("집중호우", "귀성객"), VERSION)).isEmpty();
    }

    @Test
    @DisplayName("워커가 실패해도 검색을 끊지 않는다")
    void survivesWorkerFailure() {
        // 계약 9: 확장어 부재·토큰화 실패는 degraded 가 아니다. 확장어 한 건 때문에 원 질의로
        // 충분히 찾을 수 있던 결과까지 잃으면 안 된다.
        server.expect(requestTo(BASE_URL + "/query/tokenize")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThat(adapter().tokenize(List.of("집중호우"), VERSION)).isEmpty();
    }

    @Test
    @DisplayName("확장어가 없으면 워커를 부르지 않는다")
    void doesNotCallTheWorkerWithoutTerms() {
        assertThat(adapter().tokenize(List.of(), VERSION)).isEmpty();

        server.verify();
    }

    private void respondWith(String body) {
        server.expect(requestTo(BASE_URL + "/query/tokenize"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private QueryTokenizerAdapter adapter() {
        QueryTokenizerClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(QueryTokenizerClient.class);
        return new QueryTokenizerAdapter(client);
    }
}
