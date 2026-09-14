package com.npick.search.application.port;

/**
 * patch_parse 후보 생성의 전제. 대상 신고와 그 신고가 가리키는 원 검색 실행에서 읽는다.
 *
 * <p>후보는 {@code reviewing} + {@code patch_parse} + 담당 검수자 조건에서만 만들 수 있고(F-09/F-11), 조건·변경의 대상은 그 검색의
 * {@code resolver_output_json}(교정 전 AI 원본 해석)에 실재해야 한다. 이 타입은 그 판정에 필요한 값만 담는다.
 *
 * @param status 신고 상태 (OPEN/REVIEWING/CLOSED)
 * @param resolution 처리 결과 (patch_parse 등). 아직 판정 전이면 {@code null}
 * @param reviewedById 담당 검수자. 아직 claim 전이면 {@code null}
 * @param resolverOutputJson 원 검색의 교정 전 AI 해석 JSON
 */
public record ParseContext(String status, String resolution, Long reviewedById, String resolverOutputJson) {}
