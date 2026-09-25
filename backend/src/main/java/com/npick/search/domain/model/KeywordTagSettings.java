package com.npick.search.domain.model;

import java.util.List;
import java.util.Objects;

import com.npick.tag.domain.model.TagMatchValue;

/**
 * 검색어 명사를 {@code keyword} 태그와 맞추는 설정 (S15P21A501-321).
 *
 * <p><b>가중치 하나뿐이다.</b> 확장어로 만든 조건은 후보 편입만 하고 점수를 받지 않는다 — 확장어를 구조화 점수에 쓰지 않는다는 S15P21A501-48 계약(F-05 「명시와 추정을 구분」)을
 * 설정으로 풀 수 없게 하려고 확장어 가중치를 두지 않는다.
 *
 * <p>가중치 0 은 끔이다 (코드베이스 관례 「가중치 0 = off」). 끄면 확장어 편입까지 멈춘다.
 *
 * @param weight 질의 명사 조건이 모두 맞았을 때의 가산점. 개체 축 가중평균 밖에서 더한다
 * @param conditionCap 한 질의에서 만드는 키워드 조건 수 상한. 질의 명사가 먼저 자리를 차지한다
 * @param stoplist 정보 없는 값(위치어·캡션 범용어). 태그 {@code match_value} 와 같은 규칙으로 정규화해 둔다. 순서는 버전 해시에 들어간다
 */
public record KeywordTagSettings(double weight, int conditionCap, List<String> stoplist) {

    public static final KeywordTagSettings OFF = new KeywordTagSettings(0.0, 0, List.of());

    public KeywordTagSettings {
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException("Keyword tag weight must be finite and nonnegative");
        }
        if (conditionCap < 0) {
            throw new IllegalArgumentException("Keyword condition cap must be nonnegative");
        }
        stoplist = Objects.requireNonNull(stoplist, "stoplist").stream()
                .map(value -> {
                    String normalized = TagMatchValue.normalize(value);
                    // 빈 값은 어떤 조건과도 맞을 수 없는 오타다. 조용히 넘기면 제외가 꺼진 줄 모른다.
                    if (normalized.isEmpty()) {
                        throw new IllegalArgumentException("Keyword stoplist must not contain blank values");
                    }
                    return normalized;
                })
                .distinct()
                .toList();
    }

    public boolean enabled() {
        return weight > 0 && conditionCap > 0;
    }

    /** @param matchValue {@link TagMatchValue#normalize} 를 거친 값 */
    public boolean stopped(String matchValue) {
        return stoplist.contains(matchValue);
    }
}
