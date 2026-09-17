package com.npick.search.application.query.exclusion;

import java.util.List;

import com.npick.search.domain.model.NormalizedSearch;

/** 같은 정규화 검색 조건에 승인된 장면 제외 규칙을 읽는다 (F-06, S15P21A501-58). */
public interface FindActiveSceneExclusionsQueryPort {

    /**
     * 지문뿐 아니라 정규화 원값까지 모두 같은 활성 {@code exclude_scene} 규칙만 반환한다.
     *
     * <p>해시 충돌 또는 정규화 계약 변경으로 다른 검색의 규칙이 적용되면 안 된다. 반환 순서는 규칙 ID 오름차순이다.
     */
    List<ActiveSceneExclusion> find(NormalizedSearch search);

    record ActiveSceneExclusion(long ruleId, long sceneId) {}
}
