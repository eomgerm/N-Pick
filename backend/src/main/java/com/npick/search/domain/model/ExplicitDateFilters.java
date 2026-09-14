package com.npick.search.domain.model;

import java.time.LocalDate;
import java.util.Map;

import com.npick.common.error.BusinessException;
import com.npick.search.domain.error.SearchErrorCode;

/**
 * 사용자가 UI 에서 직접 고른 날짜 필터 (FRD v3.2 F-05).
 *
 * <p>{@code docs/contracts/web-api.md} §5 의 {@code explicit_filters} 에 대응한다. 그 계약이 정본이며, 필터 축은 방송일·촬영일 두 날짜뿐이다 — 인물·장소
 * 같은 다른 축의 UI 필터는 계약에 없다.
 *
 * <p>이 값이 {@link QueryResolution.Origin#EXPLICIT_FILTER} 를 만드는 <b>유일한 통로</b>다. 리졸버가 그 출처를 주장하면 {@code AnchorVerifier} 가
 * 강등시키므로, 사용자가 실제로 고른 것만 이 자리에 온다.
 *
 * @param ranges 고른 날짜 종류만 담는다. 고르지 않은 종류는 키 자체가 없다 (계약: "선택하지 않은 날짜 종류는 key 자체를 생략한다")
 */
public record ExplicitDateFilters(Map<QueryResolution.DateField, ClosedRange> ranges) {

    public ExplicitDateFilters {
        ranges = Map.copyOf(ranges);
    }

    public static ExplicitDateFilters none() {
        return new ExplicitDateFilters(Map.of());
    }

    /**
     * 양끝을 모두 포함하는 날짜 구간.
     *
     * <p>계약의 {@code from}·{@code to} 는 둘 다 포함되는 날짜인데 {@link QueryResolution.DateWindow} 는 {@code [start, end)} 반열린이다. 그
     * 변환을 {@link #endExclusive()} 한 곳에서만 한다 — 호출부마다 {@code plusDays(1)} 를 적으면 한 군데만 빠져도 마지막 하루가 조용히 사라진다.
     */
    public record ClosedRange(LocalDate from, LocalDate to) {

        public ClosedRange {
            if (from.isAfter(to)) {
                throw new BusinessException(SearchErrorCode.EXPLICIT_FILTER_RANGE_INVERTED);
            }
        }

        public LocalDate endExclusive() {
            return to.plusDays(1);
        }
    }
}
