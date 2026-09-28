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

    /**
     * @param sceneIds 판정할 장면. 비어 있으면 조회하지 않는다. {@code null} 은 거부한다 — 조용한 빈 결과로 배선 실수를 감추지 않는다
     * @return 장면 번호 → 유효 태그. <b>키가 없는 것은 세 가지를 함께 뜻한다</b> — ① 검색 가능한 장면인데 유효 태그가 없음 ② 그 장면의 태그가 전부 반려됨 ③ <b>검색 대상 자체가
     *     아님</b>(폐기된 처리의 장면이거나 논리 삭제된 클립의 장면)
     *     <p>③ 을 구분해야 하는 호출자는 이 결과만으로 못 한다. 오래된 {@code search_result} 나 replay 실행에서 얻은 장면 번호를 넣고 키 없음을 「검증된 날짜가 없다」로
     *     읽으면, F-06 이 폐기된 처리·삭제된 클립의 장면을 제외하지 않고 통과시킨다. 검색 가능 여부는 이 판정기가 아니라 클립·처리 상태로 확인한다.
     *     {@link FindTagMatchedScenesUseCase} 방향은 애초에 그런 장면을 후보로 내지 않으므로 이 문제가 없다.
     */
    Map<Long, List<EffectiveTag>> resolve(Collection<Long> sceneIds);

    /** 검색 반영 여부와 무관하게 지정한 클립 처리의 장면들을 같은 판정 규칙으로 해석한다. */
    Map<Long, List<EffectiveTag>> resolveForRun(long clipId, long pipelineRunId, Collection<Long> sceneIds);
}
