package com.npick.search.application.port;

/**
 * 리졸버 쪽 검증이 무엇을 바꿨는지에 대한 기록.
 *
 * <p>비어 있으면 LLM 출력을 그대로 통과시켰다는 뜻이고, 그것도 남길 값이다. FRD v3.1 §7.2 가 검색마다 "실제 해석" 을 남기라고 요구한다.
 *
 * @param path 어느 항목인가. 예: {@code entities[1]}
 * @param action 무엇을 했나. {@code demoted_to_inferred} · {@code dropped} · {@code span_corrected}
 * @param reason 왜 했나. 사람이 읽는 문장
 */
public record AnchorFinding(String path, String action, String reason) {}
