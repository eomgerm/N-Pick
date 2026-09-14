package com.npick.search.domain.model;

/**
 * 검수자가 만든 비활성 patch_parse 규칙 후보. {@code search_rule} 의 {@code action='patch_parse'}·{@code active=false} 행으로 저장된다.
 *
 * <p>본문(condition/patch)은 {@link ParseRule} 형식(parse-rule/v1)의 JSON 문자열이다. 이 타입은 저장에 필요한 나머지 맥락만 담는다 — 원인 신고, 멱등 키, 교체
 * 대상. 발화·검증·승인은 후속(-49 적용, -83 검증, -84 확정) 소관이고 후보는 켜지기 전까지 일반 검색에 영향을 주지 않는다.
 *
 * @param sourceFeedbackId 원인 신고
 * @param requestKey 생성 멱등 키. 같은 신고에서 같은 키의 재요청은 후보를 중복 생성하지 않는다
 * @param conditionJson parse-rule/v1 조건 JSON
 * @param patchJson parse-rule/v1 변경 JSON
 * @param replacesRuleId 교체할 활성 규칙. 없으면 {@code null}
 */
public record ParseRuleCandidate(
        long sourceFeedbackId, String requestKey, String conditionJson, String patchJson, Long replacesRuleId) {}
