package com.npick.tag.application.query;

import java.util.List;

import com.npick.tag.domain.model.EffectiveTag;

/**
 * 태그로 걸린 후보 장면 하나 (FRD v3.1 F-05 5항).
 *
 * <p>점수가 없다. 태그 매칭은 이진이고 순위는 구조화 축 점수(S15P21A501-52)가 매긴다. 두 곳이 다 매기면 F-05 순위 원칙의 「같은 개체를 중복 계산하지 않는다」 에 걸린다.
 *
 * @param matchedTags 조건에 맞으면서 <b>판정을 통과한</b> 태그만 들어온다. 반려된 태그로 올라온 장면은 애초에 후보가 아니다. 결과 카드의 근거 설명(F-07)이 이 목록을 쓴다
 */
public record TagMatchedScene(long sceneId, long clipId, List<EffectiveTag> matchedTags) {}
