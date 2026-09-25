package com.npick.search.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 단어 검색이 실제로 사용한 실행 설정 (S15P21A501-51 의 {@code npick.search.candidate}).
 *
 * <p>dense(-53)·구조화(-52)와 달리 단어 검색 후보 조회는 결과에 설정을 실어 보내지 않는다 — {@code SceneCandidateResult} 는 점수만 들고 온다. 그래서 이 값은 조회
 * 결과가 아니라 <b>어댑터가 주입받은 것과 같은 불변 설정 빈</b>에서 읽는다. 같은 싱글턴 인스턴스이고 record 라 실행 중에 바뀌지 않으므로 "실제 사용한 설정" 과 일치한다.
 *
 * <p>{@code configVersion} 은 사람이 읽는 라벨이다. 검색 설정 버전은 이 문자열이 아니라 아래 값들을 해시해서 만든다 — 가중치를 바꾸고 라벨 갱신을 빠뜨려도 버전이 달라져야 한다
 * ({@link SearchConfigVersion}).
 *
 * @param excludedQueryTokens BM25 질의에서 뺄 범용어 토큰 ({@code 형태/품사} 소문자, 예: {@code 장면/nng}). VLM 캡션이 「~하는 장면이다」·「화면에 ~가 보인다」
 *     식으로 써서 이 말들은 활성 장면 3분의 1 가까이에 들어 있다 (S15P21A501-320, 운영 복원본 7,712 장면 실측: {@code 장면/nng} 33.4%). 질의에 남기면
 *     {@code term_set} 의 OR 로 그만큼이 후보가 된다. 목록 순서도 버전 해시에 들어간다
 */
public record LexicalSearchSettings(
        String configVersion,
        double captionWeight,
        double transcriptWeight,
        double ocrWeight,
        double expandedWeight,
        int poolSize,
        List<String> excludedQueryTokens) {

    public LexicalSearchSettings {
        Objects.requireNonNull(configVersion, "configVersion");
        excludedQueryTokens = List.copyOf(Objects.requireNonNull(excludedQueryTokens, "excludedQueryTokens"));
        if (!finiteNonNegative(captionWeight)
                || !finiteNonNegative(transcriptWeight)
                || !finiteNonNegative(ocrWeight)) {
            throw new IllegalArgumentException("Lexical field weights must be finite and nonnegative");
        }
        if (poolSize <= 0) throw new IllegalArgumentException("poolSize must be positive");
    }

    /**
     * BM25 에 걸 원 질의 토큰. 범용어를 뺀다.
     *
     * <p><b>다 빠지면 원래 토큰을 그대로 쓴다.</b> 「장면」 하나만 친 질의를 0건으로 만들면 검색 실패를 결과 없음으로 위장하게 된다 — 넓게라도 찾는 편이 사용자가 친 말에 가깝다. dense
     * 입력과 질의 지문({@code normalized_query})은 이 값을 쓰지 않는다.
     */
    public List<String> searchQueryTokens(List<String> queryTokens) {
        List<String> kept =
                queryTokens.stream().filter(token -> !isExcluded(token)).toList();
        return kept.isEmpty() ? queryTokens : kept;
    }

    /**
     * BM25 에 걸 확장어 구. 구 <b>안의</b> 범용어 토큰을 빼고, 비게 된 구는 버린다.
     *
     * <p>원 질의와 달리 되살리지 않는다. 확장어는 보조 신호라 한 구를 잃어도 원 질의가 남는다. 구에서 토큰을 빼면 {@code must} 가 헐거워지지만 빠지는 것은 장면 3분의 1 에 들어 있는
     * 말이라 걸러 주던 것이 거의 없다 — 쓸 수 없는 토큰이 든 구를 통째로 버리는 어댑터 규칙과는 다른 경우다.
     */
    public List<List<String>> searchPhrases(List<List<String>> phrases) {
        return phrases.stream()
                .map(phrase ->
                        phrase.stream().filter(token -> !isExcluded(token)).toList())
                .filter(phrase -> !phrase.isEmpty())
                .toList();
    }

    /** {@code null} 토큰은 걸러 내지 않고 어댑터에 넘긴다 — 그쪽이 이미 다루는 값이고, 불변 목록의 {@code contains(null)} 은 던진다. */
    private boolean isExcluded(String token) {
        return token != null && excludedQueryTokens.contains(token);
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
