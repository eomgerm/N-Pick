package com.npick.feedback.application.port;

import java.util.List;

/**
 * 확정이 읽는 검증 실행의 승인 스냅샷 (S15P21A501-84, F-13). {@code search_execution}(replay)의 컬럼과 {@code verification_context_json}
 * 에서 온다. 실제 생산자는 후보 검증(S15P21A501-83)이고, 이 계약이 그 산출물의 최소 형태다.
 *
 * @param executionId 검증 실행 id
 * @param resolution 이 검증이 확정할 교정 종류 (tag_correction/patch_parse)
 * @param approvedEvidenceIds tag_correction 확정 대상 근거 (patch_parse 면 빈 목록)
 * @param approvedRuleId patch_parse 활성화 대상 규칙 (tag_correction 면 {@code null})
 * @param replacedRuleId patch_parse 교체로 비활성화할 규칙 (없으면 {@code null})
 * @param stateFingerprint 검증 시점 상태 지문. 확정 시점 지문과 다르면 재검증이 필요하다(F-13 4)
 */
public record VerificationRun(
        long executionId,
        String resolution,
        List<Long> approvedEvidenceIds,
        Long approvedRuleId,
        Long replacedRuleId,
        String stateFingerprint) {}
