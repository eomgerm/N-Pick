package com.npick.search.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 순위 결합에 실제로 사용한 설정 (FRD F-05 완료 기준 「검색 설정이 달라지면 사용한 설정과 버전을 구분할 수 있다」).
 *
 * <p>여기의 숫자는 <b>확정값이 아니다.</b> FRD §11 이 순위 가중치를 실측 없이 확정하지 않기로 했고, 실측은 dense 실데이터와 성능 하네스(S15P21A501-138)가 준비된 Gate B
 * 에서 한다. 그래도 <b>실행값 자체는 비워 둘 수 없다</b> — 값이 없으면 계산이 돌지 않는다. 그래서 잠정값을 설정으로 받고 {@link WeightStatus} 로 "아직 측정 전" 임을 표시한다
 * ({@code SceneCandidateProperties} 가 쓰는 방식과 같다).
 *
 * @param rrfK RRF 상수. 기여는 {@code weight / (rrfK + rank)} 다. 클수록 상위 순위의 우위가 완만해진다
 * @param lambda 구조화 축 점수의 가산 계수. 0 이면 구조화 기여가 사라진다 (태그 전용 후보는 0 점이 되어 꼬리에 남는다)
 * @param channelWeights 채널별 <b>상대</b> 가중치. 공통 배율은 {@code R/M} 에서 상쇄되므로 합을 1 로 맞출 필요가 없다
 * @param weightStatus 이 숫자들이 실측으로 확정된 값인지
 */
public record FusionSettings(
        double rrfK, double lambda, Map<FusionChannel, Double> channelWeights, WeightStatus weightStatus) {

    public enum WeightStatus {
        EXPERIMENTAL,
        CALIBRATED
    }

    /** 이 payload 의 schema 이름. 값이 아니라 <b>구성</b>이 바뀌면 올린다 (docs/contracts/README.md 공용 규약 1). */
    public static final String SCHEMA = "search-fusion/v1";

    public FusionSettings {
        Objects.requireNonNull(weightStatus, "weightStatus");
        Objects.requireNonNull(channelWeights, "channelWeights");
        // rank 는 1 부터라 k=0 이어도 0 으로 나누지 않는다. 음수만 막으면 된다.
        if (!Double.isFinite(rrfK) || rrfK < 0) {
            throw new IllegalArgumentException("rrfK must be a finite nonnegative number");
        }
        // 상한은 두지 않는다. 「구조화만으로 전 채널 1등을 이길 수 없다」는 제품 정책이지 FRD 가 요구하는 불변식이 아니다.
        if (!Double.isFinite(lambda) || lambda < 0) {
            throw new IllegalArgumentException("lambda must be a finite nonnegative number");
        }
        var copy = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        for (var channel : FusionChannel.values()) {
            Double weight = channelWeights.get(channel);
            if (weight == null || !Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException(
                        "Every fusion channel requires a finite nonnegative weight: " + channel);
            }
            copy.put(channel, weight);
        }
        // 전 채널 0 이면 M=0 이라 R/M 이 정의되지 않는다. 구조화 단독 순위는 이번 범위가 아니므로 설정 오류로 막는다 —
        // 부팅은 되고 검색도 되는데 순위만 조용히 구조화 단독이 되는 상태를 만들지 않는다
        // (SceneCandidateProperties.isAnyFieldSearched 와 같은 이유다).
        if (copy.values().stream().noneMatch(weight -> weight > 0)) {
            throw new IllegalArgumentException("At least one fusion channel must have a positive weight");
        }
        channelWeights = Collections.unmodifiableMap(copy);
    }

    /** 가중치 0 이 곧 채널 off 다. 별도 {@code enabled} 플래그를 두지 않는다 — 두 값이 어긋난 상태를 만들지 않기 위해서다. */
    public boolean isActive(FusionChannel channel) {
        return channelWeights.get(channel) > 0;
    }

    public double weightOf(FusionChannel channel) {
        return channelWeights.get(channel);
    }
}
