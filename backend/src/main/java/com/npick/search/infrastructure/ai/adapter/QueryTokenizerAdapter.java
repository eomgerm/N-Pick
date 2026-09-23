package com.npick.search.infrastructure.ai.adapter;

import java.util.ArrayList;
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
    public List<List<String>> tokenize(List<String> terms, String expectedNormalizationVersion) {
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
            // 항목별 묶음을 그대로 보존한다 (S15P21A501-302). 펼치면 어댑터가 확장어를 OR 로 받아
            // 「중국 음식」이 중국 OR 음식 이 된다. 다만 같은 묶음 안의 중복 토큰과 완전히 같은
            // 묶음은 뺀다 — 한 구가 여러 절에서 가산돼 F-05 의 "같은 개체를 중복 계산하지 않는다" 를
            // 깨기 때문이다.
            var phrases = new LinkedHashSet<List<String>>();
            for (List<String> perTerm : response.tokens()) {
                if (perTerm == null || perTerm.isEmpty()) continue;
                var phrase = new ArrayList<>(new LinkedHashSet<>(perTerm));
                if (!isUsablePhrase(phrase)) continue;
                if (!phrase.isEmpty()) phrases.add(List.copyOf(phrase));
            }
            return List.copyOf(phrases);
        } catch (RuntimeException failed) {
            log.warn("확장어 토큰화에 실패했다. 확장어 없이 검색을 이어간다", failed);
            return List.of();
        }
    }

    /**
     * 구 하나가 후보 조회에 쓸 수 있는지 본다. 쓸 수 없는 토큰이 하나라도 있으면 <b>그 구를 통째로 버린다</b> (S15P21A501-302).
     *
     * <p>토큰만 빼고 남은 것으로 {@code must} 를 걸면 구가 그만큼 헐거워진다 — 「중국 음식」에서 한쪽이 빠지면 {@code 중국} 단독 매칭이 되어 이 티켓이 없애려던 넓은 매칭이 그대로
     * 되살아난다.
     *
     * <p><b>거르는 자리가 여기인 이유.</b> 후보 조회 어댑터에서만 버리면 호출부의 근거 설명({@code SearchCandidatePipeline.flattenForEvidence})은 버려진 구의
     * 토큰을 여전히 확장어로 싣는다. 그러면 실제로 후보를 만들지 않은 토큰이 {@code matched_keywords} 에 {@code origin=expanded} 로 떠서, 사용자가 자기가 치지 않은
     * 말 때문에 결과가 나왔다고 오해한다 — S15P21A501-234 가 막으려던 바로 그 오표시다. 창구에서 걸러 두면 후보 조회와 근거 설명이 같은 구 목록을 본다.
     */
    private static boolean isUsablePhrase(List<String> phrase) {
        for (String token : phrase) {
            if (token == null || token.isBlank()) {
                log.warn("확장어 구에 빈 토큰이 있다. 그 구 없이 검색을 이어간다");
                return false;
            }
            // 공백이 있으면 DB 에서 두 토큰으로 쪼개져 구의 의미가 조용히 달라진다.
            if (token.codePoints().anyMatch(Character::isWhitespace)) {
                log.warn("확장어 구에 공백이 든 토큰이 있다. 그 구 없이 검색을 이어간다");
                return false;
            }
        }
        return true;
    }
}
