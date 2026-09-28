package com.npick.tag.application;

import java.util.Collection;

/**
 * 태그 교정 후보 확정 — tag 도메인이 공개하는 UseCase (S15P21A501-84, F-13).
 *
 * <p>feedback 오케스트레이터가 확정 트랜잭션 안에서 호출한다. 검증이 승인한 근거를 확정({@code confirmed=true})으로 올린다. 트랜잭션 경계는 호출부가 가진다(F-13 "한 DB
 * 트랜잭션").
 */
public interface ConfirmTagCorrectionUseCase {

    /** @return 새로 확정된 근거 수 */
    int confirm(long sourceFeedbackId, Collection<Long> evidenceIds);
}
