package com.npick.search.application.query.search;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.QueryResolution;

/**
 * 검색 한 번의 입력.
 *
 * <p>이전 검색이나 대화를 이어받지 않는다 (F-05 1항). 그래서 세션·이전 실행 식별자를 받지 않는다 — 받을 자리가 있으면 언젠가 캐시로 쓰이고, §7.2 가 그것을 금지한다.
 *
 * <p><b>날짜를 domain VO 가 아니라 날짜 네 개로 받는다.</b> presentation 이 domain 을 알지 못하게 하면서 (설계 정본 §4) 같은 검증을 표현 계층에 복제하지 않기 위해서다.
 * 범위 검증({@code SRCH_400_003}·{@code SRCH_400_004})은 {@link #explicitFilters()} 가 domain 으로 옮길 때 한 번만 일어난다.
 *
 * @param rawQuery 사용자가 친 그대로. 정규화본이 아니다 — 리졸버가 원문을 봐야 anchor 의 span 을 짚을 수 있다
 * @param dateFilters 화면에서 직접 건 날짜. AI 해석과 승인 규칙보다 강하다 (F-05 4항)
 */
public record ExecuteSearchQuery(String rawQuery, DateFilters dateFilters, long memberId) {

    public ExecuteSearchQuery {
        Objects.requireNonNull(rawQuery, "rawQuery");
        Objects.requireNonNull(dateFilters, "dateFilters");
    }

    /**
     * 선택하지 않은 날짜 종류는 두 값이 모두 {@code null} 이다.
     *
     * <p>한쪽만 온 것을 열린 구간으로 보정하지 않는다 — 보정하면 사용자가 지정하지 않은 조건을 서버가 만들고, 그 값이 F-06 의 hard 제외 근거가 된다. 그 경우는
     * {@code ClosedRange} 가 {@code SRCH_400_003} 으로 막는다.
     */
    public record DateFilters(
            LocalDate broadcastFrom, LocalDate broadcastTo, LocalDate filmedFrom, LocalDate filmedTo) {

        public static DateFilters none() {
            return new DateFilters(null, null, null, null);
        }

        private boolean has(LocalDate from, LocalDate to) {
            return from != null || to != null;
        }
    }

    /** 검색이 실제로 쓰는 형태. 범위가 거꾸로이거나 한쪽만 왔으면 여기서 막힌다. */
    public ExplicitDateFilters explicitFilters() {
        Map<QueryResolution.DateField, ExplicitDateFilters.ClosedRange> ranges =
                new EnumMap<>(QueryResolution.DateField.class);
        if (dateFilters.has(dateFilters.broadcastFrom(), dateFilters.broadcastTo())) {
            ranges.put(
                    QueryResolution.DateField.BROADCAST_DATE,
                    new ExplicitDateFilters.ClosedRange(dateFilters.broadcastFrom(), dateFilters.broadcastTo()));
        }
        if (dateFilters.has(dateFilters.filmedFrom(), dateFilters.filmedTo())) {
            ranges.put(
                    QueryResolution.DateField.FILMED_DATE,
                    new ExplicitDateFilters.ClosedRange(dateFilters.filmedFrom(), dateFilters.filmedTo()));
        }
        return new ExplicitDateFilters(ranges);
    }
}
