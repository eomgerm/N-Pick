package com.npick.tag.application;

/**
 * 검수자가 확정 전에 실수로 만든 대기 중인 태그 교정 후보를 취소(폐기)한다 (S15P21A501-309).
 *
 * <p>feedback 오케스트레이터가 종료 트랜잭션에서 부르는 {@link DiscardTagCorrectionUseCase}(bulk 폐기)와 트리거가 다르다 — 검수자가 REST 로 직접 호출하며,
 * 검수 중·담당 검수자 전제를 다시 확인한다.
 */
public interface DiscardTagCorrectionCandidateUseCase {

    /** @return 폐기된 근거 수 */
    int discard(long feedbackId, long reviewerId, boolean reviewerRole);
}
