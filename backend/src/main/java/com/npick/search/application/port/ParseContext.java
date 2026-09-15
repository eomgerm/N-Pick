package com.npick.search.application.port;

/**
 * patch_parse 후보 생성의 전제. 대상 신고와 그 신고가 가리키는 원 검색 실행에서 읽는다.
 *
 * <p>후보는 {@code reviewing} + {@code patch_parse} + 담당 검수자 조건에서만 만들 수 있다(F-09/F-11). 본문 검증은 {@code resolver_output_json}
 * 의 출력 계약 버전 호환까지만 본다 — 조건·변경 대상이 이 해석에 실재하는지, explicit_filter 인지는 생성 시점에 따지지 않는다(발화 시점 -49 소관, F-11 일반화). 이 타입은 그 전제
 * 판정에 필요한 값만 담는다.
 *
 * @param status 신고 상태 (OPEN/REVIEWING/CLOSED)
 * @param resolution 처리 결과 (patch_parse 등). 아직 판정 전이면 {@code null}
 * @param reviewedById 담당 검수자. 아직 claim 전이면 {@code null}
 * @param resolverOutputJson 원 검색의 교정 전 AI 해석 JSON
 */
public record ParseContext(String status, String resolution, Long reviewedById, String resolverOutputJson) {}
