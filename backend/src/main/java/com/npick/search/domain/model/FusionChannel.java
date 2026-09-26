package com.npick.search.domain.model;

/**
 * 순위 결합에 참여하는 검색 채널 (FRD F-05 「순위 원칙」).
 *
 * <p>FRD 가 RRF 대상으로 지목한 것은 단어 검색과 텍스트 의미 검색 <b>둘</b>이다. 구조화 축 점수는 여기 없다 — 그쪽은 순위가 아니라 [0,1] 비율(+키워드 가산점,
 * S15P21A501-321)이라 RRF 기여가 아닌 가산 보정으로 들어간다 (S15P21A501-52 가 계산하고 {@code FusionSettings.lambda} 가 세기를 정한다).
 *
 * <p>{@link #LEXICAL} 은 캡션·대사·화면 글자를 <b>합계 한 채널</b>로 본다. 두 BM25 인덱스의 점수 척도가 다르다는 문제는 남아 있고, 그 보정은
 * {@code npick.search.candidate.ocr-weight} 로 한다 ({@code WordSceneCandidateAdapter} javadoc 의 승급 경로). 텍스트·화면 글자를 독립 채널로
 * 올리려면 후보 조회가 채널별 pool 을 따로 돌려줘야 하므로 이 enum 에 값을 더하는 것만으로는 되지 않는다.
 */
public enum FusionChannel {
    /** 단어 검색(BM25) 합계. {@code SceneCandidateResult.score} 내림차순 순위를 쓴다 */
    LEXICAL,
    /** 텍스트 의미 검색(pgvector). {@code DenseCandidatesResult.Candidate.rank} 를 그대로 쓴다 */
    DENSE
}
