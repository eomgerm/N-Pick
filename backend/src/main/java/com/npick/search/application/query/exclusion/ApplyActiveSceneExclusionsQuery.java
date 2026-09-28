package com.npick.search.application.query.exclusion;

import java.util.List;
import java.util.Objects;

import com.npick.search.domain.model.NormalizedSearch;

/**
 * 활성 장면 제외를 적용할 최종 순위 후보.
 *
 * @param search 정규화 검색과 exact 지문
 * @param rankedSceneIds 모든 점수·guard 처리가 끝난 검색 순위. 제외 전에 개수를 자르지 않는다
 * @param page 0-based 결과 페이지. 제외 적용 후 이 페이지 구간(page*10 .. +10)만 낸다
 */
public record ApplyActiveSceneExclusionsQuery(NormalizedSearch search, List<Long> rankedSceneIds, int page) {

    public ApplyActiveSceneExclusionsQuery {
        Objects.requireNonNull(search, "search");
        rankedSceneIds = List.copyOf(rankedSceneIds);
        if (page < 0) {
            throw new IllegalArgumentException("page 는 0 이상이어야 한다: " + page);
        }
    }

    /** 페이지 미지정 = 첫 페이지. */
    public ApplyActiveSceneExclusionsQuery(NormalizedSearch search, List<Long> rankedSceneIds) {
        this(search, rankedSceneIds, 0);
    }
}
