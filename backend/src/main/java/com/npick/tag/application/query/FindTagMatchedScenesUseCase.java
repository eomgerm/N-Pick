package com.npick.tag.application.query;

import java.util.List;

/**
 * 태그로 후보 장면을 찾는다 (FRD v3.1 F-05 5항, S15P21A501-161 → -51).
 *
 * <p>단어 검색(BM25)이 못 찾는 것을 찾는 채널이다. 교정된 태그는 정의상 캡션·대사·화면 글자에 없으므로 색인 검색으로는 절대 걸리지 않고, 값으로 직접 조회해야 한다.
 *
 * <p>입력이 형태소 토큰이 아니라 <b>구조화 조건</b> 인 것이 단어 검색과 다른 점이다. 질의 리졸버(-45)의 사건명·인물·장소·날짜창을 {@link TagCondition} 로 옮겨 넣는다.
 *
 * <p>후보 개수 상한을 두지 않는다. 태그 매칭은 이진이라 순위 근거 없이 자르면 recall 이 조용히 깎인다. 자르는 것은 구조화 축 점수(-52)가 붙은 뒤의 일이다.
 */
public interface FindTagMatchedScenesUseCase {

    /**
     * @param conditions 찾을 태그 조건. 비어 있으면 조회하지 않고 빈 목록을 돌려준다
     * @return 장면 번호 오름차순. 판정을 통과한 태그가 없는 장면은 들어오지 않는다
     */
    List<TagMatchedScene> find(List<TagCondition> conditions);
}
