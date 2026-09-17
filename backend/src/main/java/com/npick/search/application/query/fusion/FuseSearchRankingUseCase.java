package com.npick.search.application.query.fusion;

/**
 * 채널별 결과를 하나의 관련성 점수로 결합한다 (FRD F-05 「순위 원칙」, FR-SRH-001).
 *
 * <p>이 유스케이스가 소유하는 것은 <b>점수와 근거</b>다. 최신성·B-roll·계절/날씨 보정은 -55, 제외 판정은 F-06, 최종 순위 부여와 10개 선택은 -59, 저장은 -60 이다.
 *
 * <p>DB 를 직접 읽지 않는다. 입력 세 결과는 조립이 같은 트랜잭션 스냅샷에서 모아 넘긴다.
 */
public interface FuseSearchRankingUseCase {

    /** @return 적격 후보 전체의 점수와 근거. 정렬 순서는 {@code sceneId} 오름차순이며 검색 순위가 아니다 */
    FusionResult fuse(FuseSearchRankingQuery query);
}
