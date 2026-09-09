package com.npick.tag.application.query;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.npick.tag.domain.model.EffectiveTag;

/**
 * 장면들의 유효 태그를 판정한다 (S15P21A501-161).
 *
 * <p>명시 필터 비교(-58)·구조화 축 점수(-52)·결과 근거 설명(F-07)이 부르는 방향이다. 각자 태그를 읽어 판정하면 후보 추출과 어긋나 F-05 완료 기준을 깬다.
 */
public interface ResolveSceneTagsUseCase {

    /** @return 장면 번호 → 유효 태그. <b>유효 태그가 하나도 없는 장면은 키가 없다</b> */
    Map<Long, List<EffectiveTag>> resolve(Collection<Long> sceneIds);
}
