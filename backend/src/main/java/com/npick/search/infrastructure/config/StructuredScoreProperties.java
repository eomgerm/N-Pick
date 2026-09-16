package com.npick.search.infrastructure.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;

/** 미측정 가중치를 운영 확정값으로 표시하지 않는다. 값 검증은 생성되는 불변 설정이 담당한다. */
@ConfigurationProperties("npick.search.structured")
public record StructuredScoreProperties(
        StructuredScoreSettings.WeightStatus weightStatus, Map<StructuredAxis, Double> weights) {
    public StructuredScoreSettings settings() {
        return new StructuredScoreSettings(weightStatus, weights);
    }
}
