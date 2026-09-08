package com.npick.search.application.query.candidate;

/**
 * 단어 검색(BM25)이 뽑은 후보 장면 하나 (F-05 5항, FRD §11).
 *
 * <p>채널별 점수를 따로 들고 있는 이유는 두 가지다. 하나는 {@code search_result.explain_json} 이 "왜 이 장면이 올라왔는가" 를 설명해야 하고, 다른 하나는 이후 dense
 * 채널과의 RRF 결합이 단어 점수를 원본 그대로 필요로 하기 때문이다. 합계만 남기면 둘 다 복원할 수 없다.
 *
 * @param sceneId 정본 {@code scene} 의 식별자. 클립의 활성 처리에 속한 장면만 들어온다
 * @param clipId 그 장면이 속한 클립. 결과 카드와 클립 단위 태그 조회에 쓴다
 * @param score 실제 순위를 정한 값. {@code textScore + ocrWeight * ocrScore}
 * @param textScore 캡션·대사 인덱스의 BM25 점수. 필드 가중치가 이미 반영된 값이다
 * @param ocrScore 화면 글자 인덱스의 BM25 점수. 가중치를 곱하기 <b>전</b> 값이다
 */
public record SceneCandidateResult(long sceneId, long clipId, double score, double textScore, double ocrScore) {}
