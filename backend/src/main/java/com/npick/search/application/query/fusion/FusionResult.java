package com.npick.search.application.query.fusion;

import java.util.List;

import com.npick.search.domain.model.FusionChannel;

/**
 * 순위 결합의 계산 결과 — S15P21A501-55 의 soft 조정과 -60 의 {@code explain_json} 재료다.
 *
 * <p><b>최종 순위가 아니다.</b> {@code rank} 를 담지 않는다. 검색 결과의 {@code rank} 는 soft 조정(-55)과 guard·제외(F-06)까지 끝난 뒤 조립(-59)이 부여하고
 * -60 이 {@code search_result.result_rank} 에 저장한다.
 *
 * <p>{@link #candidates()} 의 정렬 순서는 {@code sceneId} 오름차순이며 <b>검색 순위가 아니다</b> (-52 의 {@code SceneScore} 와 같은 규약). 이 정렬
 * 자체는 정보를 잃지 않는다 — {@link ScoredCandidate#baseScore()} 원값이 남아 있어 -55 가 동점을 정확히 식별할 수 있다. 잃는 경우는 <b>이 순서를 최종 순위로 간주하거나
 * 원점수를 버리는 것</b>이고, 그래서 {@code rank} 를 담지 않는다. 동점은 모든 순위 정책을 적용한 뒤 마지막에 {@code sceneId} 로 가른다.
 *
 * @param candidates 적격 후보 전체. 중간 cutoff 를 두지 않는다 — 자르는 것은 guard·제외 이후 최대 10개를 고르는 조립(-59)의 몫이다
 * @param config 이 실행이 실제로 사용한 설정 전체
 * @param configVersion {@code search_execution.config_version} 에 그대로 기록할 값
 */
public record FusionResult(List<ScoredCandidate> candidates, SearchConfigSnapshot config, String configVersion) {

    public FusionResult {
        candidates = List.copyOf(candidates);
    }

    /**
     * 후보 하나의 점수와 근거.
     *
     * @param baseScore {@code R/M + λ × structured}. -55 는 이 값을 고치지 않고 별도 조정 결과를 만든다
     * @param normalizedRrf {@code R/M}. 활성 채널이 전부 1등이면 1.0 이다
     * @param structuredContribution {@code λ × structured}. λ 가 0 이면 0 이다
     * @param structuredScore -52 가 낸 [0,1] 가중평균 원값. λ 를 바꿔 다시 계산할 수 있도록 함께 남긴다
     * @param channels 채널별 상태와 기여. 기여가 0 이어도 이유가 다르므로 상태를 구분해 남긴다
     */
    public record ScoredCandidate(
            long sceneId,
            long clipId,
            double baseScore,
            double normalizedRrf,
            double structuredContribution,
            double structuredScore,
            List<ChannelContribution> channels) {
        public ScoredCandidate {
            channels = List.copyOf(channels);
        }
    }

    /**
     * 한 채널이 이 후보에게 준 기여.
     *
     * @param rank 그 채널 안에서의 1 부터 시작하는 순위. {@link ChannelState#MATCHED} 가 아니면 {@code null}
     */
    public record ChannelContribution(
            FusionChannel channel, ChannelState state, Integer rank, double weight, double contribution) {}

    /** 기여 0 의 세 가지 이유를 구분한다. 설명과 degraded 판정이 서로 다르기 때문이다. */
    public enum ChannelState {
        /** 설정상 가중치가 0 이다. 장애가 아니므로 degraded 사유가 아니다 */
        OFF,
        /** 활성 채널인데 조회가 실패했다. 조립이 degraded 로 안내할 근거다 */
        FAILED,
        /** 정상 조회했지만 이 후보가 그 채널의 결과에 없었다 */
        MISSED,
        /** 그 채널의 결과에 있었고 기여했다 */
        MATCHED
    }
}
