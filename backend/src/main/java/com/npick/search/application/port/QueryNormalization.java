package com.npick.search.application.port;

import java.util.List;

/**
 * 질의 정규화 결과.
 *
 * <p>출력이 둘인 것이 핵심이다. {@code normalizedQuery} 는 지문 재료(정렬·불용어 제거 적용)이고 {@code searchTokens} 는 BM25 질의 토큰(원 순서·불용어 유지)이다.
 * 하나로 합치면 색인된 토큰과 어긋나 검색이 0건 난다.
 *
 * @param normalizationVersion Kiwi·모델 버전이 들어 있다. BE 가 재구성하지 말고 응답 값을 그대로 {@code search_execution} 에 기록한다
 */
public record QueryNormalization(String normalizedQuery, List<String> searchTokens, String normalizationVersion) {}
