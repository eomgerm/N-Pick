package com.npick.search.domain.model;

/**
 * 명시 anchor 하나에 대한 guard 판정 (PRD §7.9, FRD v3.2 F-06).
 *
 * <p>PRD 가 「각 명시 anchor 는 verified match, unknown or unverified, verified conflict <b>중 하나</b> 로 판정한다」 로 세 값을 닫아
 * 두었다. 제외 여부만 남기면 안 되는 이유는 {@code search_result.explain_json} 때문이다 — 그 컬럼은 <b>남은 장면</b> 에만 생기고
 * {@code {"score":…, "match":…, "guard":걸러내기 판정}} 으로 guard 자리를 갖는다. 통과한 장면도 「무엇을 보고 통과시켰는가」 를 적어야 결과 카드가 F-07 의
 * 「미검증 표시」 를 그릴 수 있다.
 */
public enum GuardJudgment {

    /** 명시 조건과 같은 종류의 검증된 값이 일치했다. 관련성 계산에 쓰인다 (점수는 구조화 축이 센다). */
    VERIFIED_MATCH,

    /** 값이 없거나 미검증이다. 제외하지 않고 미상·미검증으로 표시한다. 자료 영상의 방송일 부재가 여기다. */
    UNKNOWN_OR_UNVERIFIED,

    /** 명시 조건과 같은 종류의 검증된 값이 충돌했다. F-06 이 hard 제외를 허용하는 유일한 경우다. */
    VERIFIED_CONFLICT;

    public boolean excludes() {
        return this == VERIFIED_CONFLICT;
    }
}
