package com.npick.search.application.query.search;

/**
 * 검색어를 해석한다 — 정규화·AI 해석·anchor 검증·승인 규칙·명시 필터까지.
 *
 * <p>{@link ExecuteSearchUseCase} 에서 <b>기록을 부르지 않는 부분만</b> 떼어낸 것이다. 후보 검증 재검색(F-12, S15P21A501-83)이 롤백 트랜잭션 안에서 같은 해석을
 * 태우려면 이 자리가 필요하다 — {@code execute()} 를 통째로 부르면 기록이 {@code REQUIRES_NEW} 로 롤백 밖에 커밋돼 검증 검색이 일반 검색 기록을 남긴다.
 *
 * <p>여기에 이어 {@link RankSearchCandidatesUseCase} 를 부르면 일반 검색과 같은 경로가 된다 (FRD §11 「일반 검색과 같은 코드」).
 */
public interface InterpretSearchQueryUseCase {

    InterpretedQuery interpret(ExecuteSearchQuery query);
}
