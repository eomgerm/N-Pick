package com.npick.search.domain.repository;

/**
 * 승인된 교정 규칙을 사용 중단한다 (S15P21A501-86, F-11).
 *
 * <p>중단은 {@code active} 를 끄는 것뿐이다. 규칙 본문·과거 적용 기록은 그대로 두고 하드 삭제하지 않는다. 재사용은 여기서 켜는 것이 아니라 현재 조건에서 검증·승인하는 경로(-83→-84)를
 * 지난다 — 이 포트에 활성화 메서드를 두지 않는다.
 */
public interface SearchRuleDeactivationRepository {

    /**
     * 활성 규칙을 끈다(CAS). 이미 꺼졌거나 없는 규칙은 건드리지 않는다.
     *
     * @return 새로 꺼진 규칙 수(0 또는 1)
     */
    int deactivate(long searchRuleId);

    /** 규칙이 존재하는가. deactivate 가 0 을 돌려줄 때 "없음"과 "이미 꺼짐"을 가르는 데 쓴다. */
    boolean exists(long searchRuleId);
}
