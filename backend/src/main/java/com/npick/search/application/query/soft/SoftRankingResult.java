package com.npick.search.application.query.soft;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;

/**
 * 보조 신호까지 반영한 순서 (S15P21A501-55).
 *
 * <p><b>{@link #candidates()} 의 목록 순서가 결과다.</b> {@code rank} 를 담지 않는 것은
 * {@link com.npick.search.application.query.fusion.FusionResult} 와 같은 이유다 — 번호는 F-06 의 제외까지 끝난 뒤 조립(-59)이 붙이고 -60 이
 * {@code search_result.result_rank} 에 저장한다. 여기서 번호를 붙이면 제외로 빈 자리가 생겨 두 개의 「순위」가 생긴다.
 *
 * <p>후보를 <b>버리지 않는다.</b> 입력 후보 전부가 그대로 나온다. soft 신호가 hard 제외로 변질되지 않는다는 것이 이 유스케이스의 불변식이다 (FRD F-05).
 *
 * @param candidates 보조 신호 적용 후의 순서
 * @param settings 이 실행이 실제로 사용한 보조 랭킹 설정
 */
public record SoftRankingResult(List<OrderedCandidate> candidates, SoftRankingSettings settings) {

    public SoftRankingResult {
        Objects.requireNonNull(settings, "settings");
        candidates = List.copyOf(candidates);
    }

    /**
     * 후보 하나의 보조 점수와 근거.
     *
     * @param baseScore -54 가 낸 값 <b>그대로</b>다. 보조 신호는 이 값을 고치지 않는다
     * @param softScore 활성 신호의 가중평균 {@code [0,1]}. 활성 신호가 없으면 0
     * @param signals 이 질의에서 <b>활성인 신호</b>의 값. 값이 0 인 신호도 남긴다 — 「조건이 있었는데 못 맞췄다」와 「조건 자체가 없었다」는 {@code explain_json} 에서
     *     다른 설명이다
     */
    public record OrderedCandidate(
            long sceneId, long clipId, double baseScore, double softScore, Map<SoftSignal, Double> signals) {

        public OrderedCandidate {
            signals = Map.copyOf(signals);
        }
    }
}
