package com.npick.search.domain.model;

/**
 * 명백히 잘못된 결과를 제외한 사유 (FRD v3.2 F-06, S15P21A501-56).
 *
 * <p><b>{@link IneligibleReason} 과 다르다.</b> 그쪽은 검색 대상 자체가 아닌 장면(삭제·비활성 처리)의 사유고, 이쪽은 검색 대상이 맞지만 사용자가 명시한 조건과 충돌해서 떨어진
 * 결과다. 둘을 한 enum 으로 합치면 「검색이 못 찾은 것」 과 「찾았지만 틀려서 뺀 것」 이 같은 통계로 섞이는데, §8.3 이 평가가 그런 식으로 왜곡되지 않게 하라고 요구한다.
 *
 * <p>값 이름은 응답 계약의 {@code guard_summary.reasons} 어휘와 1:1 이다 ({@code docs/contracts/web-api.md} §5.1). 그 어휘에는
 * {@code approved_scene_exclusion} 도 있지만 그것은 승인된 장면 제외 규칙의 적용이라 S15P21A501-58 소관이고, 이 enum 에 넣지 않는다 — 넣으면 이 정책이 내지 못하는
 * 값이 여기 남아 호출부가 두 곳을 다 봐야 하는지 알 수 없게 된다.
 */
public enum GuardExclusionReason {

    /**
     * 사용자가 명시한 날짜와 같은 종류의 <b>검증된</b> 날짜가 충돌한다.
     *
     * <p>F-06 이 hard 제외를 허용하는 유일한 경우다. 「같은 종류」 와 「검증된」 이 둘 다 성립해야 한다 — 방송일 조건에 촬영일을 맞대거나 미검증 값을 근거로 쓰면 맞는 결과가 사라진다.
     */
    EXPLICIT_DATE_CONFLICT("explicit_date_conflict"),

    /**
     * 승인된 사건 충돌 판정 규칙에 걸렸다.
     *
     * <p><b>현재 이 값은 나오지 않는다.</b> F-06 이 「승인된 사건 충돌 규칙이 없으면 해당 자동 제외를 사용하지 않는다」 로 확정했고 승인된 규칙이 0 건이다. 어휘를 미리 두는 이유는 §8.3
     * 이 「사건 충돌 판정 규칙이 승인되지 않았다면 해당 자동 제외를 끄고 그 지표를 <b>측정 불가</b> 로 구분한다」 를 요구하기 때문이다 — 지표를 0 으로 보고하면 규칙이 있는데 안 걸린 것과
     * 구분되지 않는다. 활성 여부는 {@code FalseHitGuardPolicy.incidentGuardActive()} 가 답한다.
     */
    APPROVED_INCIDENT_CONFLICT("approved_incident_conflict");

    private final String wireValue;

    GuardExclusionReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 응답 계약 {@code guard_summary.reasons} 에 실리는 문자열. enum 이름에서 파생시키지 않는다 — 계약이 정본이다. */
    public String wireValue() {
        return wireValue;
    }
}
