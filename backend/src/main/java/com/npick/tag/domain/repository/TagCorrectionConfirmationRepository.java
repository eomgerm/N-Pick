package com.npick.tag.domain.repository;

import java.util.Collection;

/**
 * 검수자 태그 교정 후보를 확정한다 (S15P21A501-84, F-13).
 *
 * <p>후보 근거({@code tag_evidence.confirmed=false})를 확정({@code confirmed=true})으로 올린다. 확정 대상은 검증 실행이 승인한 근거 id 이며, 원 신고
 * 범위로 한 번 더 좁혀 다른 신고의 근거가 섞여 들어오지 못하게 한다(F-13 "다른 변경안을 끼워 넣어 저장할 수 없다").
 */
public interface TagCorrectionConfirmationRepository {

    /**
     * 신고 범위 안에서 지정한 근거만 확정한다. 이미 확정된 행은 건드리지 않으므로 재요청이 중복 확정을 만들지 않는다(멱등).
     *
     * @return 새로 확정된 근거 수
     */
    int confirm(long sourceFeedbackId, Collection<Long> evidenceIds);

    /**
     * 이 신고의 대기 근거({@code confirmed=false})를 모두 폐기한다 (S15P21A501-281 no_action 종료). 확정된 근거는 건드리지 않는다.
     *
     * @return 폐기된 근거 수
     */
    int discardPending(long sourceFeedbackId);

    /**
     * 이 신고의 대기 근거 중 지정한 근거 하나만 폐기한다 (S15P21A501-309 개별 취소). 확정된 근거·다른 신고의 근거는 건드리지 않는다.
     *
     * @return 폐기된 근거 수(0 또는 1)
     */
    int discardPendingOne(long sourceFeedbackId, long evidenceId);
}
