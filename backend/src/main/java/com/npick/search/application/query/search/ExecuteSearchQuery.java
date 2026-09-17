package com.npick.search.application.query.search;

import java.util.Objects;

import com.npick.search.domain.model.ExplicitDateFilters;

/**
 * 검색 한 번의 입력.
 *
 * <p>이전 검색이나 대화를 이어받지 않는다 (F-05 1항). 그래서 세션·이전 실행 식별자를 받지 않는다 — 받을 자리가 있으면 언젠가 캐시로 쓰이고, §7.2 가 그것을 금지한다.
 *
 * @param rawQuery 사용자가 친 그대로. 정규화본이 아니다 — 리졸버가 원문을 봐야 anchor 의 span 을 짚을 수 있다
 * @param explicitFilters 화면에서 직접 건 날짜 필터. AI 해석과 승인 규칙보다 강하다 (F-05 4항)
 */
public record ExecuteSearchQuery(String rawQuery, ExplicitDateFilters explicitFilters, long memberId) {

    public ExecuteSearchQuery {
        Objects.requireNonNull(rawQuery, "rawQuery");
        Objects.requireNonNull(explicitFilters, "explicitFilters");
    }
}
