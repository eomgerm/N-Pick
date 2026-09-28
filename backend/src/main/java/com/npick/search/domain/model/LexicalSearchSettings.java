package com.npick.search.domain.model;

import java.util.List;
import java.util.Locale;
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
 * @param coverageWeight 질의 토큰 커버리지 가중치. 0 이면 해당 점수 보정이 꺼진다
 * @param excludedQueryTokens BM25 질의에서 뺄 범용어 토큰 ({@code 형태/품사} 소문자, 예: {@code 장면/nng}). VLM 캡션이 「~하는 장면이다」·「화면에 ~가 보인다」
 *     식으로 써서 이 말들은 활성 장면 3분의 1 가까이에 들어 있다 (S15P21A501-320, 운영 복원본 7,712 장면 실측: {@code 장면/nng} 33.4%). 「~를 표시·설명하는 내용」
 *     같은 캡션 서술어({@code 표시}·{@code 설명}·{@code 관련} 등, 4~10%)도 같은 경우다. 질의에 남기면 토큰 사이 OR 로 그만큼이 후보가 된다. 목록 순서도 버전 해시에 들어간다.
 *     빈 목록이면 제외를 끈다
 */
public record LexicalSearchSettings(
        String configVersion,
        double captionWeight,
        double transcriptWeight,
        double ocrWeight,
        double expandedWeight,
        double coverageWeight,
        int poolSize,
        List<String> excludedQueryTokens) {

    public LexicalSearchSettings {
        Objects.requireNonNull(configVersion, "configVersion");
        excludedQueryTokens = normalized(Objects.requireNonNull(excludedQueryTokens, "excludedQueryTokens"));
        if (!finiteNonNegative(captionWeight)
                || !finiteNonNegative(transcriptWeight)
                || !finiteNonNegative(ocrWeight)
                || !finiteNonNegative(coverageWeight)) {
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
                queryTokens.stream().filter(token -> !excludes(token)).toList();
        return kept.isEmpty() ? queryTokens : kept;
    }

    /**
     * 설정 값을 질의 토큰과 같은 형태(앞뒤 공백 없음, 소문자)로 맞춘다.
     *
     * <p>Kiwi 태그는 대문자라 운영자가 {@code 장면/NNG} 로 적기 쉽다. 그대로 두면 소문자로 오는 질의 토큰과 영영 맞지 않아 <b>제외가 조용히 꺼진다</b> — 오류도 없고 검색도 돈다.
     * 모양이 {@code 형태/품사} 가 아닌 값은 어떤 질의 토큰과도 맞을 수 없는 오타라 거부한다.
     */
    private static List<String> normalized(List<String> tokens) {
        return tokens.stream()
                .map(token -> {
                    if (token == null || token.isBlank()) {
                        throw new IllegalArgumentException("excludedQueryTokens must not contain blank tokens");
                    }
                    String value = token.strip().toLowerCase(Locale.ROOT);
                    int slash = value.lastIndexOf('/');
                    if (slash <= 0 || slash == value.length() - 1) {
                        throw new IllegalArgumentException("excludedQueryTokens must be 형태/품사: " + token);
                    }
                    return value;
                })
                .toList();
    }

    /**
     * 이 토큰이 범용어 목록에 있는가.
     *
     * <p><b>확장어 구에서 토큰을 빼는 데는 쓰지 않는다</b> — 구는 {@code must} 로 걸리므로 범용어가 들어 있어도 매칭을 좁힐 뿐 넓히지 않는다. 빼면 「자료 화면」 이 {@code 자료}
     * 단독 매칭으로 풀린다. 구에 대해서는 범용어로만 된 구를 버리고 근거 설명 칩에서 숨기는 데만 쓴다.
     *
     * <p>{@code null} 토큰은 걸러 내지 않는다 — 어댑터가 이미 다루는 값이고, 불변 목록의 {@code contains(null)} 은 던진다.
     */
    public boolean excludes(String token) {
        return token != null && excludedQueryTokens.contains(token);
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
