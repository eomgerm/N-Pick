package com.npick.search.domain.model;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 키워드 태그 매칭 설정 (S15P21A501-321). 값 검증이 부팅에서 드러나야 오타가 「조용히 꺼진 제외 목록」 이 되지 않는다. */
class KeywordTagSettingsTest {

    @Test
    void normalizesStoplistLikeTagMatchValues() {
        // 제외 판정은 TagMatchValue 로 정규화된 조건 값과 비교한다. 전각·공백이 섞인 설정 값이 그대로 남으면 영영 맞지 않는다.
        var settings = new KeywordTagSettings(0.5, 12, List.of(" 앞 ", "북​부", "앞"));

        assertThat(settings.stoplist()).containsExactly("앞", "북부");
        assertThat(settings.stopped("북부")).isTrue();
        assertThat(settings.stopped("건물")).isFalse();
    }

    @Test
    void stoplistMatchesCaseInsensitively() {
        // 키워드 조건은 equalsIgnoreCase/lower() 로 대소문자 구분 없이 매칭되므로 제외 목록도 같은 규칙을 따라야 한다.
        var settings = new KeywordTagSettings(0.5, 12, List.of("CCTV"));

        assertThat(settings.stopped("cctv")).isTrue();
        assertThat(settings.stopped("CCTV")).isTrue();
    }

    @Test
    void enabledOnlyWhenWeightAndCapArePositive() {
        assertThat(new KeywordTagSettings(0.5, 12, List.of()).enabled()).isTrue();
        assertThat(new KeywordTagSettings(0.0, 12, List.of()).enabled()).isFalse();
        assertThat(new KeywordTagSettings(0.5, 0, List.of()).enabled()).isFalse();
        assertThat(KeywordTagSettings.OFF.enabled()).isFalse();
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new KeywordTagSettings(-0.1, 12, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeywordTagSettings(Double.NaN, 12, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeywordTagSettings(0.5, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeywordTagSettings(0.5, 12, List.of("​")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeywordTagSettings(0.5, 12, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void legacyStructuredSettingsConstructorKeepsKeywordChannelOff() {
        // 321 이전 호출부(단위 테스트·순수 계산)는 키워드 채널 없이 동작해야 한다. 운영 경로는 3인자 생성자만 쓴다.
        var weights = new java.util.EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (var axis : StructuredAxis.values()) weights.put(axis, 1.0);

        var settings = new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights);

        assertThat(settings.keyword()).isEqualTo(KeywordTagSettings.OFF);
        assertThatThrownBy(() ->
                        new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights, null))
                .isInstanceOf(NullPointerException.class);
    }
}
