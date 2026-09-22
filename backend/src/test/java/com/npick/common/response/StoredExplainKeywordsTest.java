package com.npick.common.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.application.query.search.SearchExecutionResult;

import static org.assertj.core.api.Assertions.assertThat;

class StoredExplainKeywordsTest {

    @Test
    @DisplayName("origin 어휘가 쓰는 쪽과 읽는 쪽에서 같다")
    void originVocabularyMatchesTheProducer() {
        // 어휘가 두 곳에 있는 이유는 계층 규칙이다 — application 은 common 에 의존할 수 없다
        // (backend/docs/ddd-package-architecture.md §4). 그래서 한쪽만 늘리면 explain_json 저장과
        // 검색 응답은 멀쩡한데 기록 복원에서만 isRenderable 이 false 를 돌려 그 실행이 통째로
        // unavailable 이 된다. 저장은 성공하고 조회만 조용히 비는 모양이라 원인을 찾기 어렵다.
        assertThat(StoredExplainKeywords.ORIGINS)
                .containsExactlyInAnyOrder(
                        SearchExecutionResult.MatchedKeyword.ORIGIN_USER,
                        SearchExecutionResult.MatchedKeyword.ORIGIN_EXPANDED);
    }
}
