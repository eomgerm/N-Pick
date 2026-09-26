package com.npick.search.application.query.candidate;

/**
 * 단어 검색(BM25)이 뽑은 후보 장면 하나 (F-05 5항, FRD §11).
 *
 * <p>실제 순위 점수와 가산 전 점수·채널별 점수를 따로 들고 있어 실행 기록에서 순위 근거를 확인할 수 있다. RRF 는 점수 값이 아니라 후보 목록의 순서를 사용한다.
 *
 * @param sceneId 정본 {@code scene} 의 식별자. 클립의 활성 처리에 속한 장면만 들어온다
 * @param clipId 그 장면이 속한 클립. 결과 카드와 클립 단위 태그 조회에 쓴다
 * @param score 실제 단어 후보 순위를 정한 최종 점수
 * @param rawScore 커버리지 가산 전 점수. {@code textScore + ocrScore}
 * @param textScore 캡션·대사 인덱스의 BM25 점수. 필드 가중치가 이미 반영된 값이다
 * @param ocrScore 화면 글자 인덱스의 BM25 점수. <b>가중치를 곱한 뒤</b> 값이라 그 채널을 끄면 0 이다 — 끈 채널이 설명이나 재순위에 되살아나지 않게 한다
 * @param matchedQueryTokenCount 후보에 일치한 원 질의 토큰 수
 * @param queryTokenCount 원 질의 토큰 수
 * @param coverageRatio 일치 토큰 비율
 * @param coverageBonus 최종 점수에 더한 커버리지 가산점
 */
public record SceneCandidateResult(
        long sceneId,
        long clipId,
        double score,
        double rawScore,
        double textScore,
        double ocrScore,
        int matchedQueryTokenCount,
        int queryTokenCount,
        double coverageRatio,
        double coverageBonus) {

    /** SQL row mapper 가 10개 필드로 전환될 때 제거할 임시 호환 생성자. */
    public SceneCandidateResult(long sceneId, long clipId, double score, double textScore, double ocrScore) {
        this(sceneId, clipId, score, score, textScore, ocrScore, 0, 0, 0, 0);
    }
}
