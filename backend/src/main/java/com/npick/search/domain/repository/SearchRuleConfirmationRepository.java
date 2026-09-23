package com.npick.search.domain.repository;

/**
 * 해석 교정 규칙(patch_parse) 확정 쓰기 (S15P21A501-84, F-13).
 *
 * <p>검증이 승인한 후보 규칙을 활성화하고, 교체 대상 규칙이 있으면 함께 비활성화한다. baseline 주석(search_rule.active) — "검증·검수자 확인 후 승인 규칙을 저장·활성화하며 교체
 * 대상 비활성화도 함께 확정한다". 두 갱신은 호출부(feedback 오케스트레이터)의 한 트랜잭션 안에서 원자적으로 일어난다.
 */
public interface SearchRuleConfirmationRepository {

    /**
     * 신고 범위의 후보 규칙을 활성화한다. 이미 활성인 규칙은 건드리지 않아(멱등) 재요청이 다시 켜지 않는다.
     *
     * @return 새로 활성화된 규칙 수(0 또는 1)
     */
    int activate(long sourceFeedbackId, long ruleId);

    /**
     * 교체 대상 규칙을 비활성화한다. 이미 꺼진 규칙은 건드리지 않는다(멱등).
     *
     * @return 새로 비활성화된 규칙 수(0 또는 1)
     */
    int deactivate(long ruleId);

    /**
     * 이 신고의 대기 규칙 후보({@code active=false})를 모두 폐기한다 (S15P21A501-281 no_action 종료). patch_parse·exclude_scene 을 모두 지운다 —
     * 켜진(활성) 규칙은 건드리지 않는다.
     *
     * @return 폐기된 후보 수
     */
    int discardPending(long sourceFeedbackId);
}
