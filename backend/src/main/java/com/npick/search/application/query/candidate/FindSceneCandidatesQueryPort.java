package com.npick.search.application.query.candidate;

import java.util.List;

/**
 * 단어 검색으로 후보 장면을 찾는 계약 (FR-SRH-001, FRD F-05·§11).
 *
 * <p>검색 순위의 기본 축이다. 텍스트 의미 검색(-53)과 구조화 축 점수(-52)는 여기서 나온 후보 위에 결합된다.
 *
 * <p>Projection 조회이므로 Domain Repository 가 아니라 application 이 소유하는 QueryPort 다 (설계 정본 §9). Aggregate 를 복원하지 않고 순위가 붙은 후보
 * 목록만 돌려준다.
 *
 * <p>입력이 원문 질의가 아니라 <b>토큰 목록</b> 인 것이 핵심이다. 색인은 워커가 Kiwi 로 만든 토큰을 {@code pdb.whitespace} 로 자른 것이라, 질의도 같은 Kiwi 설정에서 나온
 * 토큰이어야 한다. 다르면 오류 없이 검색이 0건이 된다 ({@code docs/architecture/02-container.md}).
 *
 * <p>질의 리졸버(-45)에 의존하지 않는다. 리졸버가 실패해도 raw 질의 토큰으로 이 채널만 돌리는 것이 정해진 축소 동작이기 때문이다 (PRD "resolver 실패 시 raw query BM25
 * fallback").
 */
public interface FindSceneCandidatesQueryPort {

    /**
     * 후보 장면을 점수 높은 순으로 찾는다.
     *
     * <p>후보 개수 상한과 필드 가중치는 인자가 아니라 실행 설정이다. 호출자가 실행마다 다른 값을 넣으면 {@code search_execution.config_version} 으로 실행을 구분할 수 없게
     * 된다 (F-05 완료 기준).
     *
     * @param searchTokens Kiwi 토큰. 비어 있으면 질의하지 않고 빈 목록을 돌려준다
     * @return 점수 내림차순, 동점이면 {@code sceneId} 오름차순. 매칭이 없으면 빈 목록
     */
    List<SceneCandidateResult> findByWords(List<String> searchTokens);
}
