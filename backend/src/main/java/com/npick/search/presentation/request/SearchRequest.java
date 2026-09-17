package com.npick.search.presentation.request;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.QueryResolution;

/**
 * {@code POST /search} 요청 본문 (web-api §5).
 *
 * <p>선택하지 않은 날짜 종류는 key 자체가 없다. 날짜 필터가 하나도 없어도 {@code explicit_filters} 는 빈 object 로 온다.
 *
 * @param query 사용자가 친 원문. 정규화는 서버가 한다
 */
public record SearchRequest(
        @JsonProperty("query") @NotBlank @Size(max = 500) String query,
        @JsonProperty("explicit_filters") Filters explicitFilters) {

    /**
     * 화면에서 직접 건 날짜 필터.
     *
     * <p>{@code from}·{@code to} 는 모두 포함되는 날짜다. 시작일이 종료일보다 늦으면 {@link ExplicitDateFilters.ClosedRange} 가
     * {@code SRCH_400_004} 로 막는다.
     */
    public record Filters(
            @JsonProperty("broadcast_date") Range broadcastDate,
            @JsonProperty("filmed_date") Range filmedDate) {

        public record Range(
                @JsonProperty("from") LocalDate from,
                @JsonProperty("to") LocalDate to) {}
    }

    public ExplicitDateFilters toExplicitFilters() {
        Map<QueryResolution.DateField, ExplicitDateFilters.ClosedRange> ranges =
                new EnumMap<>(QueryResolution.DateField.class);
        if (explicitFilters != null) {
            put(ranges, QueryResolution.DateField.BROADCAST_DATE, explicitFilters.broadcastDate());
            put(ranges, QueryResolution.DateField.FILMED_DATE, explicitFilters.filmedDate());
        }
        return new ExplicitDateFilters(ranges);
    }

    private void put(
            Map<QueryResolution.DateField, ExplicitDateFilters.ClosedRange> ranges,
            QueryResolution.DateField field,
            Filters.Range range) {
        if (range == null) {
            return;
        }
        // from·to 중 하나만 온 것은 범위가 아니다. 한쪽을 열린 구간으로 보정하면 사용자가 지정하지
        // 않은 조건을 서버가 만들어 내는 셈이고, 그 값이 F-06 의 hard 제외 근거로 쓰인다.
        ranges.put(field, new ExplicitDateFilters.ClosedRange(range.from(), range.to()));
    }
}
