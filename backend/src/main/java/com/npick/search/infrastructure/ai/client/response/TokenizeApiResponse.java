package com.npick.search.infrastructure.ai.client.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 토큰화 응답 (resolver-api §2.2).
 *
 * <p>{@code tokens} 는 요청의 {@code texts} 와 같은 길이·같은 순서다. 토큰이 0 개인 항목은 빈 배열이며 오류가 아니다.
 *
 * @param normalizationVersion 토큰 경계를 결정한 설정 식별자. {@code /query/resolve} 응답의 같은 이름 필드와 같아야 한다 — 다르면 그 토큰과 그 검색이 서로 다른
 *     규칙으로 만들어진 것이다
 */
public record TokenizeApiResponse(
        @JsonProperty("tokens") List<List<String>> tokens,
        @JsonProperty("normalization_version") String normalizationVersion) {}
