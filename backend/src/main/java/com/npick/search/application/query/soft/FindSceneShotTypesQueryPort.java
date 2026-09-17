package com.npick.search.application.query.soft;

import java.util.Collection;
import java.util.Map;

import com.npick.search.domain.model.ShotType;

/**
 * 장면들의 {@code scene.shot_type} 을 읽는다 (S15P21A501-55).
 *
 * <p>태그가 아니라 컬럼이라 {@code ResolveSceneTagsUseCase} 경로를 타지 않는다 (F-04).
 *
 * <p><b>적격 판정을 하지 않는다.</b> 입력은 이미 -52 의 적격 판정을 통과한 장면이다. 여기서 다시 거르면 같은 판정이 두 곳에 생긴다.
 */
public interface FindSceneShotTypesQueryPort {

    /**
     * @param sceneIds 조회할 장면. 비어 있으면 조회하지 않는다
     * @return 장면 번호 → 샷 유형. <b>키가 없으면 그 장면을 찾지 못한 것</b>이며, 호출자는 가점 없음으로 다룬다. {@code shot_type} 은 {@code NOT NULL} 이라 찾은
     *     장면에는 항상 값이 있다
     */
    Map<Long, ShotType> find(Collection<Long> sceneIds);
}
