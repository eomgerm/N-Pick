package com.npick.search.infrastructure.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;

/**
 * npick.search.soft — 보조 랭킹 실행 설정 (FRD §11 「실행 환경 설정: 개발하면서 실측으로 정함」, S15P21A501-55).
 *
 * <p>완료 조건 「boost 값을 설정으로 분리한다. 실측 없이 숫자를 확정하지 않는다」의 자리다. {@code weight-status: EXPERIMENTAL} 이 미측정임을 실행 기록에 남긴다. 값 검증은
 * 생성되는 불변 설정이 담당한다 ({@code StructuredScoreProperties} 와 같은 구조).
 */
@ConfigurationProperties("npick.search.soft")
public record SoftRankingProperties(
        FusionSettings.WeightStatus weightStatus, Double tieEpsilon, Map<SoftSignal, Double> weights) {

    public SoftRankingSettings settings() {
        // weights 가 비어 있으면 SoftRankingSettings 가 「신호마다 가중치가 필요하다」로 부팅을 막는다. 여기서 널을 빈 맵으로 바꾸는 것은 기본값을
        // 채우는 것이 아니라 NPE 대신 그 검증에 걸리게 하려는 것이다. tieEpsilon 만 널이 실제 기본값(0)으로 떨어진다 — 가장 보수적인 값이라
        // 빠뜨렸을 때 도달하는 상태가 이미 안전하다.
        return new SoftRankingSettings(
                weights == null ? Map.of() : weights, tieEpsilon == null ? 0 : tieEpsilon, weightStatus);
    }
}
