package com.npick.search.application.query.fusion;

import java.util.List;
import java.util.Objects;

import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.application.query.dense.DenseCandidatesResult;
import com.npick.search.application.query.structured.StructuredScoresResult;

/**
 * 순위 결합의 입력 — 두 RRF 채널(lexical·dense)과 구조화 점수의 결과를 받는다.
 *
 * <p>구조화 점수는 {@link com.npick.search.domain.model.FusionChannel} 이 아니다. RRF 기여가 아니라 별도 항으로 더해지므로 「세 채널」이라고 부르지 않는다.
 *
 * <p>조회를 직접 하지 않는 이유는 트랜잭션·순서·degraded 판정이 조립(-59)의 책임이기 때문이다. -59 가 단어·dense 후보를 모아 -52 에 넘기고, 그 결과 셋을 여기로 가져온다.
 *
 * @param lexicalCandidates -51 의 후보. 점수 내림차순이며 그 순서가 곧 lexical 채널의 순위다
 * @param denseCandidates -53 의 결과. dense 채널이 꺼져 있어 실행하지 않았으면 {@code null} 이다. 실행했다가 실패한 것과 구분해야 하므로 빈 결과로 대신하지 않는다
 * @param structuredScores -52 의 결과. 적격 후보 집합과 [0,1] 축 점수를 함께 들고 온다
 */
public record FuseSearchRankingQuery(
        List<SceneCandidateResult> lexicalCandidates,
        DenseCandidatesResult denseCandidates,
        StructuredScoresResult structuredScores) {

    public FuseSearchRankingQuery {
        Objects.requireNonNull(structuredScores, "structuredScores");
        lexicalCandidates = List.copyOf(lexicalCandidates);
    }
}
