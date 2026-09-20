package com.npick.search.application.query.search;

import com.npick.search.application.port.QueryResolutionResult;

/**
 * 검색어를 해석한다 — 정규화·AI 해석·anchor 검증·승인 규칙·명시 필터까지.
 *
 * <p>{@link ExecuteSearchUseCase} 에서 <b>기록을 부르지 않는 부분만</b> 떼어낸 것이다. 후보 검증 재검색(F-12, S15P21A501-83)이 롤백 트랜잭션 안에서 같은 해석을
 * 태우려면 이 자리가 필요하다 — {@code execute()} 를 통째로 부르면 기록이 {@code REQUIRES_NEW} 로 롤백 밖에 커밋돼 검증 검색이 일반 검색 기록을 남긴다.
 *
 * <p>여기에 이어 {@link RankSearchCandidatesUseCase} 를 부르면 일반 검색과 같은 경로가 된다 (FRD §11 「일반 검색과 같은 코드」).
 *
 * <p>{@link #resolve} / {@link #interpretFromResolution} 은 {@link #interpret} 을 둘로 쪼갠 자리다(S15P21A501-219). {@code resolve} 만
 * 외부 리졸버 HTTP 를 태우고 flip 상태와 무관하다 — 검증 재검색이 이 부분을 롤백 트랜잭션 <b>밖</b>에서 먼저 부르고, {@code interpretFromResolution} 만
 * (활성 규칙 조회가 flip 반영 상태를 읽어야 하므로) 트랜잭션 <b>안</b>에서 부른다.
 */
public interface InterpretSearchQueryUseCase {

    InterpretedQuery interpret(ExecuteSearchQuery query);

    Resolution resolve(ExecuteSearchQuery query);

    InterpretedQuery interpretFromResolution(ExecuteSearchQuery query, Resolution resolution);

    /** {@link #resolve} 의 결과물 — 외부 리졸버가 낸 raw 값, anchor 검증을 거친 resolved 값, 걸린 시간(ms). */
    record Resolution(QueryResolutionResult raw, QueryResolutionResult resolved, int parseMs) {}
}
