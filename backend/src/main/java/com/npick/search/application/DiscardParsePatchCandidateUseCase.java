package com.npick.search.application;

/**
 * 검수자가 확정 전에 실수로 만든 대기 중인 해석 교정(patch_parse) 후보를 취소(폐기)한다 (S15P21A501-309).
 *
 * <p>feedback 오케스트레이터가 종료 트랜잭션에서 부르는 {@link DiscardSearchRuleCandidatesUseCase}(patch_parse·exclude_scene bulk 폐기)와 달리,
 * 검수자가 REST 로 직접 호출하며 patch_parse 후보만 지운다 — 같은 신고의 exclude_scene 후보는 남긴다.
 */
public interface DiscardParsePatchCandidateUseCase {

    /** @return 폐기된 후보 수 */
    int discard(long feedbackId, long reviewerId, boolean reviewerRole);
}
