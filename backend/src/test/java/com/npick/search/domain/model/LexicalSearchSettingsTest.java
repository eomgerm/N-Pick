package com.npick.search.domain.model;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 캡션 범용어를 BM25 질의에서 빼는 규칙 (S15P21A501-320). */
class LexicalSearchSettingsTest {

    private static final List<String> GENERIC = List.of("장면/nng", "보이/vv", "화면/nng", "모습/nng");

    private final LexicalSearchSettings settings =
            new LexicalSearchSettings("candidate-v3", 1.0, 1.0, 1.0, 0.3, 200, GENERIC);

    @Test
    @DisplayName("원 질의 토큰에서 범용어만 뺀다")
    void removesGenericTermsFromQueryTokens() {
        assertThat(settings.searchQueryTokens(List.of("화면/nng", "보이/vv", "자막/nng")))
                .containsExactly("자막/nng");
    }

    @Test
    @DisplayName("인코딩까지 같아야 뺀다 — 품사가 다르거나 옛 형식(형태만)이면 남긴다")
    void matchesOnlyTheEncodedToken() {
        // 보이/vv 는 빼도 보이/nnp(고유명사) 는 다른 말이다. 질의는 항상 `형태/품사` 소문자로 오므로
        // 옛 형식 `장면` 은 질의 쪽에서 나오지 않지만, 나오더라도 설정과 다른 값이라 건드리지 않는다.
        assertThat(settings.searchQueryTokens(List.of("보이/nnp", "장면", "장면/NNG", "장면/nng")))
                .containsExactly("보이/nnp", "장면", "장면/NNG");
    }

    @Test
    @DisplayName("원 질의 토큰이 전부 범용어면 원래 토큰을 그대로 쓴다")
    void keepsTheOriginalTokensWhenEveryTokenIsGeneric() {
        assertThat(settings.searchQueryTokens(List.of("화면/nng", "장면/nng"))).containsExactly("화면/nng", "장면/nng");
    }

    @Test
    @DisplayName("null 토큰은 걸러 내지 않고 넘긴다")
    void passesNullTokensThrough() {
        // 불변 목록의 contains(null) 은 던진다. null 처리는 어댑터가 이미 하는 일이다.
        assertThat(settings.searchQueryTokens(Arrays.asList("화재/nng", null, "장면/nng")))
                .containsExactly("화재/nng", null);
    }

    @Test
    @DisplayName("확장어 구 안의 범용어를 빼고 비게 된 구는 버린다")
    void removesGenericTermsInsidePhrasesAndDropsEmptyPhrases() {
        assertThat(settings.searchPhrases(
                        List.of(List.of("큰불/nng", "장면/nng"), List.of("모습/nng", "화면/nng"), List.of("소방차/nng"))))
                .containsExactly(List.of("큰불/nng"), List.of("소방차/nng"));
    }

    @Test
    @DisplayName("목록이 비어 있으면 아무것도 빼지 않는다")
    void anEmptyListExcludesNothing() {
        var none = new LexicalSearchSettings("candidate-v3", 1.0, 1.0, 1.0, 0.3, 200, List.of());
        assertThat(none.searchQueryTokens(List.of("화재/nng", "장면/nng"))).containsExactly("화재/nng", "장면/nng");
        assertThat(none.searchPhrases(List.of(List.of("장면/nng")))).containsExactly(List.of("장면/nng"));
    }
}
