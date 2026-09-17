package com.npick.search.application.query.soft;

import java.util.List;
import java.util.Objects;

import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.domain.model.QueryResolution;

/**
 * 보조 랭킹의 입력 (S15P21A501-55).
 *
 * <p>{@link FusionResult} 전체가 아니라 후보 목록만 받는다. 이 유스케이스는 설정 스냅샷·{@code configVersion} 을 읽지 않고, 소유하지도 않는다 — 실행 설정 기록은
 * {@code SearchConfigSnapshot} 한 곳이다.
 *
 * @param candidates -54 의 적격 후보 전체. {@code sceneId} 오름차순이며 <b>검색 순위가 아니다</b>. 자르지 않은 목록을 그대로 받는다 — 상위 몇 개만 받으면 보조 신호가
 *     잘린 뒤의 순서만 만지게 된다
 * @param finalResolution 교정 규칙까지 적용된 최종 해석. 어떤 신호가 활성인지가 여기서 정해진다
 */
public record AdjustSoftRankingQuery(List<FusionResult.ScoredCandidate> candidates, QueryResolution finalResolution) {

    public AdjustSoftRankingQuery {
        Objects.requireNonNull(finalResolution, "finalResolution");
        candidates = List.copyOf(candidates);
    }
}
