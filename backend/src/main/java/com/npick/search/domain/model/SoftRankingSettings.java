package com.npick.search.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 보조 랭킹에 실제로 사용한 설정 (S15P21A501-55, FRD F-05 완료 기준 「검색 설정이 달라지면 사용한 설정과 버전을 구분할 수 있다」).
 *
 * <p>{@link FusionSettings} 와 마찬가지로 여기의 숫자는 <b>확정값이 아니다.</b> FRD §11 이 순위 가중치를 실측 없이 확정하지 않기로 했으므로 잠정값을 설정으로 받고
 * {@link FusionSettings.WeightStatus} 로 측정 전임을 남긴다.
 *
 * <p><b>전 신호 0 을 막지 않는다.</b> {@link FusionSettings} 는 전 채널 0 이면 {@code R/M} 이 정의되지 않아 부팅을 막지만, 보조 신호가 전부 꺼진 상태는 보정 없는
 * 정상 검색이다. 끄는 것이 곧 설정 오류인 쪽과 아닌 쪽을 같은 규칙으로 다루지 않는다.
 *
 * @param weights 신호별 <b>상대</b> 가중치. 보조 점수는 활성 신호의 가중평균이라 공통 배율은 상쇄된다
 * @param tieEpsilon 동점으로 묶는 <b>버킷의 폭</b>. {@code 0} 이면 정확히 같을 때만 동점이고, {@link #MAX_TIE_EPSILON} 이상은 거부한다. 「차이가 이 값
 *     이하면 동점」이 <b>아니다</b> — 경계 양쪽은 차이가 작아도 갈린다. 자세한 것은
 *     {@link com.npick.search.domain.policy.SoftRankingPolicy#compareBase}
 * @param weightStatus 이 숫자들이 실측으로 확정된 값인지
 */
public record SoftRankingSettings(
        Map<SoftSignal, Double> weights, double tieEpsilon, FusionSettings.WeightStatus weightStatus) {

    /** 이 payload 의 schema 이름. 값이 아니라 <b>구성</b>이 바뀌면 올린다 (docs/contracts/README.md 공용 규약 1). */
    public static final String SCHEMA = "search-soft/v1";

    /**
     * {@code tieEpsilon} 의 상한(미만). <b>척도에서 유도한 값이 아니라 임의로 고른 보수적 가드레일이다.</b>
     *
     * <p>버킷 비교가 주는 참인 성질은 하나다 — 같은 버킷이면 두 {@code baseScore} 의 차가 {@code tieEpsilon} 미만이다. 따라서 보조 신호는 <b>{@code
     * tieEpsilon} 이상 벌어진 순서를 뒤집지 못한다.</b> 이 문장은 λ 와 무관하게 정확하다.
     *
     * <p>뒤집혀도 되는 폭이 얼마인지는 계산으로 나오지 않는다. {@code baseScore} 의 상한이 {@code 1 + λ} 이고 λ 에 상한이 없어 「전체 범위의 몇 퍼센트」로도 고정되지
     * 않는다. 그래서 이 값은 F-05 「관련 없는 B-roll 이나 최신 영상이 관련성이 높은 장면보다 무조건 앞서지 않게 한다」를 지키는 쪽으로 <b>작게</b> 잡은 것이고, 실측(Gate B)에서
     * 조정 대상이다. 숫자 자체를 근거 있는 값처럼 읽지 않는다.
     */
    public static final double MAX_TIE_EPSILON = 0.1;

    public SoftRankingSettings {
        Objects.requireNonNull(weightStatus, "weightStatus");
        Objects.requireNonNull(weights, "weights");
        if (!Double.isFinite(tieEpsilon) || tieEpsilon < 0 || tieEpsilon >= MAX_TIE_EPSILON) {
            throw new IllegalArgumentException("tieEpsilon must be within [0, " + MAX_TIE_EPSILON + ")");
        }
        var copy = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        for (SoftSignal signal : SoftSignal.values()) {
            Double weight = weights.get(signal);
            if (weight == null || !Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException("Every soft signal requires a finite nonnegative weight: " + signal);
            }
            copy.put(signal, weight);
        }
        weights = Collections.unmodifiableMap(copy);
    }

    /** 가중치 0 이 곧 신호 off 다. 별도 {@code enabled} 플래그를 두지 않는다 — 두 값이 어긋난 상태를 만들지 않기 위해서다. */
    public boolean isActive(SoftSignal signal) {
        return weights.get(signal) > 0;
    }

    public double weightOf(SoftSignal signal) {
        return weights.get(signal);
    }
}
