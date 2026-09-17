package com.npick.search.domain.policy;

import java.util.Map;

import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;

/**
 * 보조 신호의 세기와 적용 범위 (FRD F-05 「순위 원칙」, S15P21A501-55).
 *
 * <pre>
 * soft(d) = Σ w_s × v_s(d) / Σ w_s      활성 신호만. 값이 0 이어도 분모에 남는다
 * 정렬    = (baseScore 버킷 내림차순, soft 내림차순, sceneId 오름차순)
 * </pre>
 *
 * <p><b>보조 점수를 {@code baseScore} 에 더하지 않는다.</b> 더하면 가중치를 잘못 잡았을 때 관련성이 낮은 장면이 높은 장면을 실제로 추월할 수 있고, 그것을 막으려면 보정 상한을 실측으로
 * 맞춰야 한다. 대신 <b>동점 구간 안에서만</b> 순서를 가르게 하면 F-05 의 "관련 없는 B-roll 이나 최신 영상이 관련성이 높은 장면보다 무조건 앞서지 않게 한다" 가 <b>가중치를 어떻게 잡아도</b>
 * 성립한다. FRD §11 이 실측 없이 숫자를 확정하지 말라고 한 조건에서 고를 수 있는 쪽이다.
 *
 * <p><b>다만 「설정값과 무관하게」는 아니다.</b> 뒤집힐 수 있는 폭을 정하는 손잡이가 정확히 하나 있다 — {@code tieEpsilon} 이다. 버킷 비교가 주는 보장은
 * 「같은 버킷이면 두 {@code baseScore} 의 차가 {@code tieEpsilon} 미만」이고, 뒤집어 말하면 <b>{@code tieEpsilon} 이상 벌어진 순서는 절대 뒤집히지
 * 않는다</b>는 것이다. 그래서 이 값이 클수록 보조 점수가 실제로 순서를 가르는 폭이 그대로 넓어지고,
 * {@link SoftRankingSettings#MAX_TIE_EPSILON} 이 그 폭에 보수적인 가드레일을 건다.
 *
 * <p>대가는 {@code tieEpsilon} 이 0 이면 보조 신호가 정확한 동점에서만 작동한다는 것이다. 태그로만 들어온 후보는 두 채널이 모두 {@code MISSED} 라 구조화 점수가 같으면 실제로
 * 동점이 되므로 그 구간에서 의미가 있고, 폭을 넓히려면 {@code tieEpsilon} 을 키운다.
 */
public final class SoftRankingPolicy {

    /**
     * 이 후보의 보조 점수 {@code [0,1]}.
     *
     * @param signalValues <b>이 질의에서 활성인 신호</b>의 값 {@code [0,1]}. 값이 없는 신호도 {@code 0} 으로 담아야 한다 — 빼면 정보가 없는 항목이 가점을 받는
     *     것과 같아진다 (F-05). 활성 신호가 없으면 빈 맵이다
     */
    public double softScore(Map<SoftSignal, Double> signalValues, SoftRankingSettings settings) {
        // 가중치를 활성 최댓값으로 먼저 접는다. 바로 더하면 Double.MAX_VALUE 급 가중치 둘에서 분자·분모가 모두
        // Infinity 가 되어 몫이 NaN 이고, NaN 은 Double.compare 가 최댓값으로 다뤄 그 후보가 목록 맨 앞으로 간다.
        // 나눈 뒤에는 각 항이 [0,1] 이라 활성 신호 수만큼만 쌓여 넘칠 수 없다. 공통 배율이 상쇄된다는 계약도
        // 이렇게 해야 실제로 성립한다 — 접기 전에는 극단값에서만 깨졌다.
        double scale = 0;
        for (SoftSignal signal : signalValues.keySet()) {
            scale = Math.max(scale, settings.weightOf(signal));
        }
        if (scale == 0) return 0;

        double weighted = 0;
        double denominator = 0;
        for (var entry : signalValues.entrySet()) {
            double value = entry.getValue();
            if (!(value >= 0) || !(value <= 1)) {
                throw new IllegalArgumentException("Soft signal value must be within [0,1]: " + entry.getKey());
            }
            double weight = settings.weightOf(entry.getKey()) / scale;
            weighted += weight * value;
            denominator += weight;
        }
        return denominator == 0 ? 0 : weighted / denominator;
    }

    /**
     * 두 {@code baseScore} 의 순위 비교. 결과가 {@code 0} 인 구간에서만 보조 점수가 순서를 가른다.
     *
     * <p>{@code epsilon} 이 0 보다 크면 {@code floor(base/epsilon)} 버킷으로 비교한다. 몫이 {@code Infinity} 가 될 만큼 작은 {@code epsilon} 은 정확
     * 비교로 접는다 — 버킷 폭이 0 에 수렴하는 구간의 의도된 동작이고, 접지 않으면 서로 다른 점수가 동점으로 보여 보조 점수가 관련성을 이긴다. {@code |a-b| <= epsilon} 식 관용 비교를 쓰지 않는 이유는
     * 그것이 <b>추이적이지 않기</b> 때문이다 — {@code a~b}, {@code b~c} 인데 {@code a≁c} 가 성립하면 {@code Comparator} 계약이 깨져
     * {@code List.sort} 가 던진다. 버킷 경계 바로 양쪽은 차이가 작아도 갈리는데, 그 대신 정렬이 늘 성립한다.
     */
    public int compareBase(double left, double right, double epsilon) {
        if (epsilon <= 0) {
            return Double.compare(left, right);
        }
        double leftBucket = Math.floor(left / epsilon);
        double rightBucket = Math.floor(right / epsilon);
        // epsilon 이 아주 작으면 두 몫이 모두 Infinity 가 되어 서로 다른 점수가 동점으로 보인다. 그 구간에서 의도한 동작은
        // 「버킷 폭이 0 에 수렴」, 즉 정확 비교다. 여기서 접지 않으면 보조 점수가 관련성을 이기는 경로가 열린다.
        if (!Double.isFinite(leftBucket) || !Double.isFinite(rightBucket)) {
            return Double.compare(left, right);
        }
        return Double.compare(leftBucket, rightBucket);
    }
}
