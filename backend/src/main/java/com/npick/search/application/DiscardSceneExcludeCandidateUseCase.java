package com.npick.search.application;

/**
 * 검수자가 확정 전에 실수로 만든 장면 제외 후보를 취소(폐기)한다 (S15P21A501-281).
 *
 * <p>생성({@link CreateSceneExcludeCandidateUseCase})의 역이지만 트리거가 다르다 — feedback 오케스트레이터가 no_action 종료 트랜잭션에서 부르는
 * {@link DiscardSearchRuleCandidatesUseCase}와 달리, 검수자가 REST 로 직접 호출한다.
 */
public interface DiscardSceneExcludeCandidateUseCase {

    /** @return 폐기된 후보 수 */
    int discard(long feedbackId, long reviewerId, boolean reviewerRole);
}
