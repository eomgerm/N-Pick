package com.npick.common.persistence;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.springframework.stereotype.Component;

/**
 * 교정 상태 지문 (S15P21A501-83·-84, FRD F-13.3·4). 4축 정규 문자열을 해시 없이 낸다 — 사람이 읽고 재현하기 쉬워
 * 검증(-83)과 확정(-84)의 어긋남을 빨리 잡는다.
 *
 * <p>축: {@code rules}(전체 활성 규칙 집합) · {@code tags}(확정된 검수자 근거 집합) · {@code config}(검색 설정 버전) ·
 * {@code run}(신고 대상 클립의 현재 처리). -83 기록과 -84 확정이 <b>같은 이 컴포넌트</b>를 부른다. 한쪽만 넓히면 확정이 영구 불일치로 막히므로
 * 지문 규칙 변경은 반드시 이 한 파일에서 한다.
 *
 * <p>{@code rules} 축은 {@code action} 을 가리지 않는다 — patch_parse 뿐 아니라 exclude_scene 등 활성 규칙 전부가 검색 결과를
 * 바꾸므로, 검증 이후 어느 규칙이 활성/비활성으로 바뀌어도 재검증 대상이다(F-13 4). -84 {@code CurrentCorrectionStateQueryAdapter}
 * 는 독자적인 SQL 없이 이 컴포넌트에 위임한다 — 대칭이 코드로 강제되는 단일 진실 공급원이다.
 */
@Component
public class CorrectionStateFingerprint {

    private final EntityManager em;
    private final SearchConfigVersionSupplier configVersion;

    public CorrectionStateFingerprint(EntityManager em, SearchConfigVersionSupplier configVersion) {
        this.em = em;
        this.configVersion = configVersion;
    }

    public String compute(long feedbackId) {
        return "rules=" + joinedIds("SELECT search_rule_id FROM npick.search_rule "
                        + "WHERE active = true ORDER BY search_rule_id")
                + ";tags=" + joinedIds("SELECT evidence_id FROM npick.tag_evidence "
                        + "WHERE source = 'reviewer_feedback' AND confirmed = true ORDER BY evidence_id")
                + ";config=" + configVersion.currentConfigVersion()
                + ";run=" + joinedIds("SELECT DISTINCT c.active_pipeline_run_id FROM npick.feedback f "
                        + "JOIN npick.search_result sr ON sr.search_result_id = f.search_result_id "
                        + "JOIN npick.scene s ON s.scene_id = sr.scene_id "
                        + "JOIN npick.clip c ON c.clip_id = s.clip_id "
                        + "WHERE f.feedback_id = :feedbackId AND c.active_pipeline_run_id IS NOT NULL "
                        + "ORDER BY c.active_pipeline_run_id", feedbackId);
    }

    private String joinedIds(String sql) {
        return joinedIds(sql, null);
    }

    private String joinedIds(String sql, Long feedbackId) {
        Query query = em.createNativeQuery(sql);
        if (feedbackId != null) {
            query.setParameter("feedbackId", feedbackId);
        }
        List<?> rows = query.getResultList();
        return rows.stream().map(v -> ((Number) v).longValue()).map(String::valueOf).collect(Collectors.joining(","));
    }
}
