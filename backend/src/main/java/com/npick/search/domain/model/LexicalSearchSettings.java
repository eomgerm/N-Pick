package com.npick.search.domain.model;

import java.util.Objects;

/**
 * 단어 검색이 실제로 사용한 실행 설정 (S15P21A501-51 의 {@code npick.search.candidate}).
 *
 * <p>dense(-53)·구조화(-52)와 달리 단어 검색 후보 조회는 결과에 설정을 실어 보내지 않는다 — {@code SceneCandidateResult} 는 점수만 들고 온다. 그래서 이 값은 조회
 * 결과가 아니라 <b>어댑터가 주입받은 것과 같은 불변 설정 빈</b>에서 읽는다. 같은 싱글턴 인스턴스이고 record 라 실행 중에 바뀌지 않으므로 "실제 사용한 설정" 과 일치한다.
 *
 * <p>{@code configVersion} 은 사람이 읽는 라벨이다. 검색 설정 버전은 이 문자열이 아니라 아래 값들을 해시해서 만든다 — 가중치를 바꾸고 라벨 갱신을 빠뜨려도 버전이 달라져야 한다
 * ({@link SearchConfigVersion}).
 */
public record LexicalSearchSettings(
        String configVersion,
        double captionWeight,
        double transcriptWeight,
        double ocrWeight,
        double expandedWeight,
        int poolSize) {

    public LexicalSearchSettings {
        Objects.requireNonNull(configVersion, "configVersion");
        if (!finiteNonNegative(captionWeight)
                || !finiteNonNegative(transcriptWeight)
                || !finiteNonNegative(ocrWeight)) {
            throw new IllegalArgumentException("Lexical field weights must be finite and nonnegative");
        }
        if (poolSize <= 0) throw new IllegalArgumentException("poolSize must be positive");
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
