package com.npick.search.infrastructure.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.npick.search.domain.model.KeywordTagSettings;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;

/** 미측정 가중치를 운영 확정값으로 표시하지 않는다. 값 검증은 생성되는 불변 설정이 담당한다. */
@ConfigurationProperties("npick.search.structured")
public record StructuredScoreProperties(
        StructuredScoreSettings.WeightStatus weightStatus, Map<StructuredAxis, Double> weights, Keyword keyword) {

    /** 박싱 타입인 이유: 키가 빠진 것을 0 으로 읽으면 채널이 조용히 꺼진다. 빠졌으면 부팅을 막는다. */
    public record Keyword(Double weight, Integer conditionCap, List<String> stoplist) {}

    public StructuredScoreSettings settings() {
        if (keyword == null
                || keyword.weight() == null
                || keyword.conditionCap() == null
                || keyword.stoplist() == null) {
            throw new IllegalStateException(
                    "npick.search.structured.keyword.weight · condition-cap · stoplist 가 모두 필요하다 (S15P21A501-321)");
        }
        return new StructuredScoreSettings(
                weightStatus,
                weights,
                new KeywordTagSettings(keyword.weight(), keyword.conditionCap(), keyword.stoplist()));
    }
}
