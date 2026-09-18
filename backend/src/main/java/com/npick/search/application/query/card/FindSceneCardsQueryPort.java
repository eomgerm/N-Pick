package com.npick.search.application.query.card;

import java.util.Collection;
import java.util.Map;

/**
 * 최종 순위에 남은 장면들의 카드 표시값을 읽는다 (web-api §5 의 {@code data.results[]}).
 *
 * <p>순위·제외가 모두 끝난 뒤 <b>한 번</b> 부른다. 후보 전체에 부르지 않는다 — 카드 값은 최대 10건에만 필요한데 후보는 수백 건이고, 대사 원문까지 함께 읽기 때문이다.
 *
 * @return {@code sceneId} 로 찾는 카드. 없는 장면은 <b>키가 없다</b> — 조립이 그 장면을 결과에서 빼는 근거로 쓴다
 */
public interface FindSceneCardsQueryPort {

    Map<Long, SceneCard> find(Collection<Long> sceneIds);
}
