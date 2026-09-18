package com.npick.search.infrastructure.ai.client.request;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 토큰화 요청 (resolver-api §2.1).
 *
 * <p>상한(64항목·항목당 200자)은 워커가 422 로 막는다. 여기서 미리 자르지 않는 이유는 자르는 순간 어느 확장어가 빠졌는지 아무 데도 안 남기 때문이다 — 넘치면 422 를 받고 확장어 없이 검색을
 * 이어간다.
 */
public record TokenizeApiRequest(@JsonProperty("texts") List<String> texts) {}
