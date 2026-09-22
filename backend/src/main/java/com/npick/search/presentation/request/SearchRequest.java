package com.npick.search.presentation.request;

import java.time.LocalDate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.npick.search.application.query.search.ExecuteSearchQuery;

/**
 * {@code POST /search} 요청 본문 (web-api §5).
 *
 * <p>선택하지 않은 날짜 종류는 key 자체가 없다. 날짜 필터가 하나도 없어도 {@code explicit_filters} 는 빈 object 로 온다.
 *
 * <p>여기서 domain 타입을 만들지 않는다 (설계 정본 §4). 범위 검증은 application 이 한 번만 한다 — 두 곳에서 하면 한쪽을 고칠 때 다른 쪽이 남는다.
 *
 * @param query 사용자가 친 원문. 정규화는 서버가 한다
 */
public record SearchRequest(
        @JsonProperty("query") @NotBlank @Size(min = 2, max = 500, message = "검색어는 2글자 이상 입력해 주세요") String query,
        @JsonProperty("explicit_filters") Filters explicitFilters,
        @JsonProperty("page") @Min(0) @Max(39) Integer page) {

    // 상한은 후보 pool 을 다 넘긴 뒤로 둔다 — 안 그러면 has_next=true 인데 다음 page 가 400 이다
    // (S15P21A501-251 리뷰 #2). lexical 200 ∪ dense 200 = 최대 400 후보 / 페이지 크기 10 =
    // 40 페이지(0~39)이므로 39 가 상한이다. 이 위 페이지는 어떤 pool 에도 결과가 없다.

    /** page 를 지정하지 않은 요청은 첫 페이지(0)다 — 하위호환. */
    public int pageOrDefault() {
        return page == null ? 0 : page;
    }

    /**
     * 화면에서 직접 건 날짜 필터.
     *
     * <p>{@code from}·{@code to} 는 모두 포함되는 날짜다.
     */
    public record Filters(
            @JsonProperty("broadcast_date") Range broadcastDate,
            @JsonProperty("filmed_date") Range filmedDate) {

        public record Range(
                @JsonProperty("from") LocalDate from,
                @JsonProperty("to") LocalDate to) {}
    }

    public ExecuteSearchQuery.DateFilters toDateFilters() {
        if (explicitFilters == null) {
            return ExecuteSearchQuery.DateFilters.none();
        }
        return new ExecuteSearchQuery.DateFilters(
                from(explicitFilters.broadcastDate()),
                to(explicitFilters.broadcastDate()),
                from(explicitFilters.filmedDate()),
                to(explicitFilters.filmedDate()));
    }

    private LocalDate from(Filters.Range range) {
        return range == null ? null : range.from();
    }

    private LocalDate to(Filters.Range range) {
        return range == null ? null : range.to();
    }
}
