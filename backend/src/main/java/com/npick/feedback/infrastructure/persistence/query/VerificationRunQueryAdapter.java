package com.npick.feedback.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import com.npick.common.error.BusinessException;
import com.npick.feedback.application.error.ConfirmCorrectionErrorCode;
import com.npick.feedback.application.port.VerificationRun;
import com.npick.feedback.application.port.VerificationRunPort;

/**
 * 확정 근거가 되는 검증 실행을 읽는다 (S15P21A501-84). 성공한 replay 이면서 이 신고의 실행일 때만 돌려준다 — 다른 신고·일반 검색 실행을 확정 근거로 쓸 수 없다(F-13 2).
 *
 * <p>승인 스냅샷은 {@code verification_context_json} 에 담긴다. 실제 생산자는 후보 검증(S15P21A501-83)이고, 여기서 읽는 형태가 그 최소
 * 계약이다({@link VerificationRun}).
 */
@Repository
class VerificationRunQueryAdapter implements VerificationRunPort {

    private final EntityManager em;
    private final ObjectMapper objectMapper = new ObjectMapper();

    VerificationRunQueryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public Optional<VerificationRun> find(long executionId, long feedbackId) {
        List<?> rows = em.createNativeQuery("SELECT verification_context_json::text FROM npick.search_execution "
                        + "WHERE search_execution_id = :eid AND execution_type = 'replay' "
                        + "AND replay_of_feedback_id = :fid AND status = 'succeeded' "
                        + "AND verification_context_json IS NOT NULL")
                .setParameter("eid", executionId)
                .setParameter("fid", feedbackId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        VerificationRun run = parse(executionId, (String) rows.get(0));
        // 스냅샷이 판정별 필수 필드·타입을 갖추지 못하면 확정 근거로 삼을 수 없다 — 없는 실행처럼 거부한다(F-12 "후보가 적용되지 않으면 확정 불가").
        // 빈 근거로 tag_correction 을 확정하거나, 잘못된 타입(문자열·객체 id)이 0 으로 변환돼 0 행을 무시한 채 CLOSED 되는 것을 막는다.
        return run != null && isComplete(run) ? Optional.of(run) : Optional.empty();
    }

    private static boolean isComplete(VerificationRun run) {
        if (run.stateFingerprint() == null || run.stateFingerprint().isBlank()) {
            return false;
        }
        String resolution = run.resolution();
        boolean hasRule = run.approvedRuleId() != null;
        if ("tag_correction".equals(resolution)) {
            return !run.approvedEvidenceIds().isEmpty();
        }
        if ("patch_parse".equals(resolution) || "exclude_scene".equals(resolution)) {
            return hasRule && resolution.equals(run.approvedRuleAction());
        }
        if ("correction".equals(resolution)) {
            return hasRule
                    ? isRuleAction(run.approvedRuleAction())
                    : !run.approvedEvidenceIds().isEmpty();
        }
        return false;
    }

    /** 스냅샷을 엄격히 읽는다. id 는 정수·양수만 허용하고, 타입이 어긋나면 {@code null}(무효)을 돌려준다. JSON 자체가 깨지면 실패시킨다(§6.2). */
    private VerificationRun parse(long executionId, String json) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_VERIFICATION_RUN);
        }
        List<Long> evidenceIds = new ArrayList<>();
        JsonNode ids = node.get("approved_evidence_ids");
        // -84의 단수 규칙 확정 계약으로 복수 후보를 일부만 확정해서는 안 된다.
        JsonNode candidateRules = node.get("candidate_rules");
        if (candidateRules != null && (!candidateRules.isArray() || candidateRules.size() > 1)) {
            return null;
        }
        if (ids != null && !ids.isNull()) {
            if (!ids.isArray()) {
                return null;
            }
            for (JsonNode id : ids) {
                if (!isPositiveId(id)) {
                    return null;
                }
                evidenceIds.add(id.asLong());
            }
        }
        Long approvedRuleId = strictOptionalId(node.get("approved_rule_id"));
        Long replacedRuleId = strictOptionalId(node.get("replaced_rule_id"));
        if (approvedRuleId == INVALID || replacedRuleId == INVALID) {
            return null;
        }
        String resolution = text(node, "resolution");
        String approvedRuleAction = text(node, "approved_rule_action");
        if (approvedRuleAction == null
                && ("patch_parse".equals(resolution) || "exclude_scene".equals(resolution))) {
            approvedRuleAction = resolution;
        }
        if (approvedRuleAction != null && !isRuleAction(approvedRuleAction)) {
            return null;
        }
        return new VerificationRun(
                executionId,
                resolution,
                List.copyOf(evidenceIds),
                approvedRuleId,
                replacedRuleId,
                approvedRuleAction,
                text(node, "state_fingerprint"));
    }

    private static boolean isRuleAction(String action) {
        return "patch_parse".equals(action) || "exclude_scene".equals(action);
    }

    private static final Long INVALID = Long.MIN_VALUE;

    private static boolean isPositiveId(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.asLong() > 0;
    }

    /** 없거나 null 이면 {@code null}, 정수·양수면 그 값, 그 외 타입이면 {@link #INVALID}(무효 표시). */
    private static Long strictOptionalId(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return isPositiveId(value) ? value.asLong() : INVALID;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
