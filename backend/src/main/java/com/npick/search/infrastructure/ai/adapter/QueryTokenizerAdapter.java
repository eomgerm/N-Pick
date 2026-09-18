package com.npick.search.infrastructure.ai.adapter;

import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.npick.search.application.query.expansion.TokenizeExpandedTermsPort;
import com.npick.search.infrastructure.ai.client.QueryTokenizerClient;
import com.npick.search.infrastructure.ai.client.request.TokenizeApiRequest;
import com.npick.search.infrastructure.ai.client.response.TokenizeApiResponse;

/**
 * 확장어 토큰화. <b>실패를 예외로 올리지 않는다</b> (S15P21A501-48 계약 9 「확장어 부재·토큰화 실패는 degraded 가 아니다」).
 *
 * <p>확장어는 보조 신호다. 한 건 때문에 검색을 끊으면 원 질의로 충분히 찾을 수 있던 결과까지 잃는다. 실패하면 빈 목록을 돌려주고 원 질의 토큰만으로 검색이 이어진다.
 *
 * <p>사유는 로그에만 남긴다. 사용자에게 알릴 만한 기능 저하가 아니고, 계약이 {@code degraded_reasons} 어휘를 세 값으로 닫아 두었다.
 */
@Component
class QueryTokenizerAdapter implements TokenizeExpandedTermsPort {

    private static final Logger log = LoggerFactory.getLogger(QueryTokenizerAdapter.class);

    private final QueryTokenizerClient client;

    QueryTokenizerAdapter(QueryTokenizerClient client) {
        this.client = client;
    }

    @Override
    public List<String> tokenize(List<String> terms, String expectedNormalizationVersion) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        try {
            TokenizeApiResponse response = client.tokenize(new TokenizeApiRequest(List.copyOf(terms)));
            if (response == null || response.tokens() == null) {
                log.warn("확장어 토큰화 응답이 비었다. 확장어 없이 검색을 이어간다");
                return List.of();
            }
            // resolver-api §2.4-4: len(tokens) == len(texts) 이고 순서가 보존된다. 어긋나면 어느
            // 확장어의 토큰인지 알 수 없다.
            if (response.tokens().size() != terms.size()) {
                log.warn(
                        "확장어 토큰화 응답의 항목 수가 다르다: 요청 {} / 응답 {}",
                        terms.size(),
                        response.tokens().size());
                return List.of();
            }
            // §2.4-1: 두 값이 다르면 그 토큰과 그 검색은 서로 다른 규칙으로 만들어진 것이다. 섞으면
            // 색인이 하지 않는 경계로 질의해 오류 없이 0건이 된다.
            if (expectedNormalizationVersion != null
                    && !expectedNormalizationVersion.equals(response.normalizationVersion())) {
                log.warn(
                        "확장어 토큰화 정규화 버전이 다르다: 질의 {} / 토큰 {}",
                        expectedNormalizationVersion,
                        response.normalizationVersion());
                return List.of();
            }
            // 펼쳐서 중복을 뺀다. 확장어 목록 자체에 같은 토큰이 여러 번 나올 수 있고, 그대로
            // 넘기면 한 토큰이 여러 절에서 가산돼 F-05 의 "같은 개체를 중복 계산하지 않는다" 를 깬다.
            var tokens = new LinkedHashSet<String>();
            for (List<String> perTerm : response.tokens()) {
                if (perTerm != null) {
                    tokens.addAll(perTerm);
                }
            }
            return List.copyOf(tokens);
        } catch (RuntimeException failed) {
            log.warn("확장어 토큰화에 실패했다. 확장어 없이 검색을 이어간다", failed);
            return List.of();
        }
    }
}
