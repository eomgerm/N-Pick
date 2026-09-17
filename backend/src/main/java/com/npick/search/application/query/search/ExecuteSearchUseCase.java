package com.npick.search.application.query.search;

/**
 * 정규화 → 해석 → 규칙 → 순위 → guard → 제외 전 단계를 하나의 검색 실행으로 조립한다 (FRD F-05, S15P21A501-59).
 *
 * <p>이 유스케이스가 소유하는 것은 <b>순서와 트랜잭션 경계</b>다. 각 단계의 계산은 이미 자기 유스케이스가 있고, 여기서는 무엇을 언제 부르는지와 그 결과를 어떻게 합쳐 하나의 실행으로
 * 남기는지만 정한다.
 *
 * <p>최초 검색과 재검색은 각각 <b>새 실행</b>이다. 과거 결과를 다음 검색의 해석 캐시로 쓰지 않는다 (§7.2).
 */
public interface ExecuteSearchUseCase {

    SearchExecutionResult execute(ExecuteSearchQuery query);
}
