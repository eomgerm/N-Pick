package com.npick.search.application;

/**
 * 규칙 사용 중단 요청 (S15P21A501-86).
 *
 * @param ruleId 대상 규칙
 * @param reviewerRole 요청자가 검수자 역할인가
 * @param active 요청한 목표 상태. {@code true}(재활성화)는 검증 경로를 거쳐야 하므로 거부된다
 * @param reason 중단 사유. 받아서 검증하되 저장하지는 않는다(FRD 감사 de-scope)
 */
public record DeactivateSearchRuleCommand(long ruleId, boolean reviewerRole, boolean active, String reason) {}
