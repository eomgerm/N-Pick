package com.npick.search.domain.model;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;


/**
 * {@code scene.shot_type} 의 닫힌 4값 (FRD F-04).
 *
 * <p><b>태그가 아니다.</b> 계절·날씨·장면 유형은 {@code tag.tag_type} 으로 갔는데 샷 유형만 칸으로 남았고, baseline 의 컬럼 주석이 그 이유를 적는다 — 랭킹이 매 검색마다
 * 읽고 평가 지표에도 있다. 그래서 {@code TagType} 에 없고 여기에 있다.
 *
 * <p>{@link #UNKNOWN} 은 「B-roll 이 아님」이 아니라 <b>「모름」</b>이다. 보조 가산점에서는 {@code ANCHOR}·{@code INTERVIEW} 와 함께 0 을 받는데, 이는 둘을
 * 같게 보는 것이 아니라 <b>어느 쪽도 가점 대상이 아니기</b> 때문이다 — 「모름」에 가점을 주지 않는 것이 F-05 「정보가 없는 항목에 가점을 주지 않는다」다. 이 구분이 실제로
 * 갈리는 것은 벌점을 주거나 분모에서 빼려 할 때이고, 보조 랭킹은 둘 다 하지 않는다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ShotType {
    ANCHOR("anchor"),
    INTERVIEW("interview"),
    B_ROLL("b_roll"),
    UNKNOWN("unknown");

    /** {@code scene.shot_type} 에 실제로 들어 있는 문자열. enum 이름과 우연히 닮아도 파생시키지 않는다 — 컬럼 값이 정본이다. */
    private final String storedValue;

    private static final Map<String, ShotType> BY_STORED_VALUE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ShotType::storedValue, Function.identity()));

    /**
     * 저장된 문자열을 샷 유형으로 바꾼다.
     *
     * <p>{@code shot_type} 은 {@code varchar} 라 DB 가 4값을 강제하지 못한다. 그래서 <b>모르는 값이 실제로 올 수 있고</b>, 이 메서드는 그것을 예외가 아니라
     * 빈 결과로 돌려준다. 호출자는 가점 없음으로 다루고 이상값은 로그로 남긴다 — 보조 신호 하나 때문에 검색 요청 전체가 실패하지 않게 하는 것이 F-05 의 soft 규칙에 맞다.
     *
     * <p>{@link #UNKNOWN} 으로 접지 않는 이유는 그것이 파이프라인이 실제로 저장한 값이기 때문이다. 깨진 값을 정상 4값 중 하나로 위장시키지 않는다.
     */
    public static Optional<ShotType> parse(String storedValue) {
        // Map.of 산물은 get(null) 에 NPE 를 던진다. 「모르는 값은 빈 결과」라는 계약이 null 에서만 갈리지 않게 먼저 거른다.
        return storedValue == null ? Optional.empty() : Optional.ofNullable(BY_STORED_VALUE.get(storedValue));
    }
}
