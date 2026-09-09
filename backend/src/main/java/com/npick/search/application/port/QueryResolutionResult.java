package com.npick.search.application.port;

import java.util.List;

import com.npick.search.application.error.QueryResolverErrorCode;

/**
 * 리졸버 호출 한 번의 결과.
 *
 * <p>정규화는 항상 채워지고, 해석은 성공했을 때만 채워진다. FRD v3.1 §6.2 가 "AI 해석 실패·시간 초과 → 원 검색어의 단어 검색으로 전환" 을 요구하는데 그 BM25 에
 * {@link QueryNormalization#searchTokens()} 가 필요하기 때문이다. <b>해석 실패를 예외로 던지면 그 토큰을 잃어버려 fallback 자체가 불가능해진다.</b>
 *
 * <p>버전이 셋인 이유는 셋 다 결과를 바꾸기 때문이다 — schema 가 바뀌면 필드가, 프롬프트가 바뀌면 해석이, 모델이 바뀌면 판단이 달라진다. §7.2 기록에 그대로 넣는다.
 *
 * @param resolution 해석 결과. 실패했으면 {@code null}
 * @param failure 실패 사유. 성공했으면 {@code null}
 */
public record QueryResolutionResult(
        QueryNormalization normalization,
        QueryResolution resolution,
        List<AnchorFinding> findings,
        String resolutionSchemaVersion,
        String promptVersion,
        String modelVersion,
        QueryResolverErrorCode failure) {

    public QueryResolutionResult {
        if (normalization == null) {
            throw new IllegalArgumentException("normalization 은 항상 있어야 한다");
        }
        // 정확히 하나. 둘 다 없으면 호출부가 판단할 근거가 없고, 둘 다 있으면 어느 쪽이 진짜인지
        // 모른다. 어느 경우든 조용히 흘려보내면 검색이 잘못된 상태로 이어진다.
        if ((resolution == null) == (failure == null)) {
            throw new IllegalArgumentException("resolution 과 failure 중 정확히 하나여야 한다");
        }
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** 해석을 쓸 수 있는가. 거짓이면 {@link #failure()} 를 degraded 사유로 기록하고 BM25 로 간다. */
    public boolean isResolved() {
        return resolution != null;
    }
}
