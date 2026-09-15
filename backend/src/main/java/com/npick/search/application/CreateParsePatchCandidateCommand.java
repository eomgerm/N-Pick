package com.npick.search.application;

/**
 * patch_parse 후보 생성 요청. condition/patch 는 parse-rule/v1 JSON 문자열이다.
 *
 * @param feedbackId 대상 신고
 * @param reviewerId 요청 검수자
 * @param reviewerRole 요청자가 검수자 역할인가. 편집기자면 거부한다
 * @param requestKey 생성 멱등 키
 * @param conditionJson parse-rule/v1 조건 JSON
 * @param patchJson parse-rule/v1 변경 JSON
 * @param replacesRuleId 교체할 활성 규칙. 없으면 {@code null}
 */
public record CreateParsePatchCandidateCommand(
        long feedbackId,
        long reviewerId,
        boolean reviewerRole,
        String requestKey,
        String conditionJson,
        String patchJson,
        Long replacesRuleId) {}
