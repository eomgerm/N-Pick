package com.npick.search.infrastructure.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;

/**
 * npick.search.fusion — 순위 결합 실행 설정 (FRD §11 「실행 환경 설정: 개발하면서 실측으로 정함」).
 *
 * <p>여기의 숫자는 확정값이 아니다. {@code weight-status: EXPERIMENTAL} 이 그 사실을 실행 기록에 남긴다. 값 검증은 생성되는 불변 설정이 담당한다
 * ({@code StructuredScoreProperties} 와 같은 구조).
 */
@ConfigurationProperties("npick.search.fusion")
public record FusionProperties(
        FusionSettings.WeightStatus weightStatus,
        Double rrfK,
        Double lambda,
        Map<FusionChannel, Double> channelWeights) {

    public FusionSettings settings() {
        return new FusionSettings(
                rrfK == null ? 0 : rrfK,
                lambda == null ? 0 : lambda,
                channelWeights == null ? Map.of() : channelWeights,
                weightStatus);
    }
}
