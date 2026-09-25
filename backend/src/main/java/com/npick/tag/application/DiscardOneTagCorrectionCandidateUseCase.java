package com.npick.tag.application;

/**
 * 검수자가 대기 중인 태그 교정 후보 하나만 취소(폐기)한다 (S15P21A501-309).
 *
 * <p>{@link DiscardTagCorrectionCandidateUseCase}(이 신고의 대기 후보 전체 폐기)와 전제·오류 코드가 같고 대상만 근거 하나로 좁다. FE 가 추가한 태그 하나를 취소할 때
 * 다른 대기 후보를 지우지 않게 한다.
 */
public interface DiscardOneTagCorrectionCandidateUseCase {

    /** @return 폐기된 근거 수(0 또는 1) */
    int discardOne(long feedbackId, long evidenceId, long reviewerId, boolean reviewerRole);
}
