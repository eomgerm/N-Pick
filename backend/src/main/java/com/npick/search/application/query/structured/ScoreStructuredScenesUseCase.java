package com.npick.search.application.query.structured;

/** #54가 어느 RRF 채널 구성을 택하든 사용할 수 있는 구조화 점수·근거. 최종 순위는 계산하지 않는다. */
public interface ScoreStructuredScenesUseCase {
    StructuredScoresResult score(ScoreStructuredScenesQuery query);
}
