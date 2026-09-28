package com.npick.search.domain.model;

/**
 * 보조 랭킹 신호 (FRD F-05 「순위 원칙」, S15P21A501-55).
 *
 * <p>여기 있는 것은 전부 <b>soft</b> 다 — 후보를 걸러내지 않고, 정렬을 강제하지 않으며, {@code baseScore} 를 바꾸지도 않는다. 적용 방법은
 * {@link com.npick.search.domain.policy.SoftRankingPolicy} 의 계약이고, 그 계약이 "관련 없는 B-roll 이나 최신 영상이 관련성이 높은 장면보다 무조건 앞서지
 * 않게 한다" 를 <b>가중치와 무관하게</b> 성립시킨다. 보조 점수가 순서를 가르는 폭을 정하는 것은 {@code tieEpsilon} 하나이고 그쪽은 상한으로 막는다
 * ({@link SoftRankingSettings#MAX_TIE_EPSILON}).
 *
 * <p><b>군중 밀도는 여기 없다.</b> FRD v3.2 §1.2·§11 에서 범위 밖으로 확정됐다. 태그 유형에도 없고 순위 신호로도 쓰지 않는다.
 *
 * <p>구조화 축({@link StructuredAxis})과 갈리는 기준은 「FRD 가 관련성으로 세는가」다. 계절·날씨는 F-04 의 태그 유형이지만 F-05 의 구조화 축 열거에 없어 이쪽으로 온다. 장면
 * 유형({@code scene_type})은 구조화 축이므로 여기 없다 — 한 값을 양쪽에서 세면 F-05 「같은 개체를 중복 계산하지 않는다」를 깬다.
 */
public enum SoftSignal {

    /**
     * 최신성. 검색 의도가 {@link QueryResolution.Intent#RECENT_SCENE} 일 때만 활성이다.
     *
     * <p>의도가 없을 때도 켜면 "최신 영상이 관련성 높은 장면보다 앞선다" 로 가는 상시 편향이 된다. 값의 출처는 {@code broadcast_date} 태그다.
     */
    RECENCY,

    /**
     * B-roll. 의도와 무관하게 상시 활성이다.
     *
     * <p>리졸버의 {@link QueryResolution.Intent} 에 b-roll 값이 없어 의도로 게이트할 수단 자체가 없다. baseline 의 {@code scene.shot_type} 컬럼
     * 주석과 {@code ai/docs/vlm-metadata.md} 가 "랭킹이 매 검색마다 shot_type 을 읽고 b_roll 에 보조 가산점" 으로 적은 것과 같은 동작이다. 값의 출처는
     * {@link ShotType} 이고 태그가 아니다 (F-04).
     */
    B_ROLL,

    /** 계절. 질의 해석의 {@code classifications} 에 계절 조건이 있을 때만 활성이다. 값의 출처는 {@code season} 태그다 (F-04). */
    SEASON,

    /** 날씨. 질의 해석의 {@code classifications} 에 날씨 조건이 있을 때만 활성이다. 값의 출처는 {@code weather} 태그다 (F-04). */
    WEATHER
}
