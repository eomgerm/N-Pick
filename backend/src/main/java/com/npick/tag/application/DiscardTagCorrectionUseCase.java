package com.npick.tag.application;

/**
 * 태그 교정 대기 후보 폐기 — tag 도메인이 공개하는 UseCase (S15P21A501-281).
 *
 * <p>feedback 오케스트레이터가 no_action 종료 트랜잭션 안에서 호출한다. 이 신고가 만든 미확정 근거({@code tag_evidence.confirmed=false})를 지운다. 트랜잭션 경계는
 * 호출부가 가진다.
 */
public interface DiscardTagCorrectionUseCase {

    /** @return 폐기된 근거 수 */
    int discardPending(long sourceFeedbackId);
}
