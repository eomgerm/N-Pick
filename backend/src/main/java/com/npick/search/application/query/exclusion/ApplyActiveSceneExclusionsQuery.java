package com.npick.search.application.query.exclusion;

import java.util.List;
import java.util.Objects;

import com.npick.search.domain.model.NormalizedSearch;

/**
 * 활성 장면 제외를 적용할 최종 순위 후보.
 *
 * @param search 정규화 검색과 exact 지문
 * @param rankedSceneIds 모든 점수·guard 처리가 끝난 검색 순위. 제외 전에 개수를 자르지 않는다
 */
public record ApplyActiveSceneExclusionsQuery(NormalizedSearch search, List<Long> rankedSceneIds) {

    public ApplyActiveSceneExclusionsQuery {
        Objects.requireNonNull(search, "search");
        rankedSceneIds = List.copyOf(rankedSceneIds);
    }
}
