package com.npick.feedback.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npick.common.error.BusinessException;
import com.npick.feedback.application.error.ConfirmCorrectionErrorCode;
import com.npick.feedback.application.port.VerificationRun;
import com.npick.feedback.application.port.VerificationRunPort;

/**
 * 확정 근거가 되는 검증 실행을 읽는다 (S15P21A501-84). 성공한 replay 이면서 이 신고의 실행일 때만 돌려준다 — 다른 신고·일반 검색 실행을 확정 근거로 쓸 수 없다(F-13 2).
 *
 * <p>승인 스냅샷은 {@code verification_context_json} 에 담긴다. 실제 생산자는 후보 검증(S15P21A501-83)이고, 여기서 읽는 형태가 그 최소 계약이다({@link
 * VerificationRun}).
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
        return Optional.of(parse(executionId, (String) rows.get(0)));
    }

    private VerificationRun parse(long executionId, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<Long> evidenceIds = new ArrayList<>();
            JsonNode ids = node.get("approved_evidence_ids");
            if (ids != null && ids.isArray()) {
                ids.forEach(id -> evidenceIds.add(id.asLong()));
            }
            return new VerificationRun(
                    executionId,
                    text(node, "resolution"),
                    List.copyOf(evidenceIds),
                    optionalLong(node, "approved_rule_id"),
                    optionalLong(node, "replaced_rule_id"),
                    text(node, "state_fingerprint"));
        } catch (Exception e) {
            // 스냅샷을 읽지 못하면 확정 근거로 삼을 수 없다. 조용히 넘어가지 않고 실패시킨다(§6.2).
            throw new BusinessException(ConfirmCorrectionErrorCode.NOT_VERIFICATION_RUN);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }
}
