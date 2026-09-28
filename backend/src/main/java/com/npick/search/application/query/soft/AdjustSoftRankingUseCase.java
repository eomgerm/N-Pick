package com.npick.search.application.query.soft;

/**
 * 보조 랭킹 규칙을 적용한다 (FRD F-05 「순위 원칙」, S15P21A501-55).
 *
 * <p>이 유스케이스가 소유하는 것은 <b>동점 구간의 순서</b>다. 관련성 점수 계산은 -54, 제외 판정은 F-06, 최종 순위 부여와 10개 선택은 -59, 저장은 -60 이다.
 *
 * <p>여기의 어떤 신호도 후보를 버리거나 {@code baseScore} 를 고치지 않는다. "관련 없는 B-roll 이나 최신 영상이 관련성이 높은 장면보다 무조건 앞서지 않게 한다" 는 설정값이 아니라
 * {@link com.npick.search.domain.policy.SoftRankingPolicy} 의 비교 방식으로 지킨다.
 */
public interface AdjustSoftRankingUseCase {

    /** @return 입력 후보 <b>전부</b>를 보조 신호 적용 후 순서로 담은 결과 */
    SoftRankingResult adjust(AdjustSoftRankingQuery query);
}
