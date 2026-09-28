package com.npick.common.persistence;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.springframework.stereotype.Component;

/**
 * 교정 상태 지문 (S15P21A501-83·-84, FRD F-13.3·4). 6축 정규 문자열을 해시 없이 낸다 — 사람이 읽고 재현하기 쉬워 검증(-83)과 확정(-84)의 어긋남을 빨리 잡는다.
 *
 * <p>축: {@code rules}(전체 활성 규칙 집합) · {@code tags}(확정된 검수자 근거 집합) · {@code config}(검색 설정 버전) · {@code run}(신고 대상 클립의 현재
 * 처리) · {@code pending_rules}(이 신고의 대기 규칙 후보) · {@code pending_tags}(이 신고의 미확정 검수자 태그 후보). -83 기록과 -84 확정이 <b>같은 이
 * 컴포넌트</b>를 부른다. 한쪽만 넓히면 확정이 영구 불일치로 막히므로 지문 규칙 변경은 반드시 이 한 파일에서 한다.
 *
 * <p>{@code rules} 축은 {@code action} 을 가리지 않는다 — patch_parse 뿐 아니라 exclude_scene 등 활성 규칙 전부가 검색 결과를 바꾸므로, 검증 이후 어느 규칙이
 * 활성/비활성으로 바뀌어도 재검증 대상이다(F-13 4). -84 {@code CurrentCorrectionStateQueryAdapter} 는 독자적인 SQL 없이 이 컴포넌트에 위임한다 — 대칭이 코드로
 * 강제되는 단일 진실 공급원이다.
 *
 * <p>{@code pending_rules}·{@code pending_tags} 축은 이 신고(feedbackId)에 딸린, 아직 활성/확정되지 않은 후보를 센다. 이 축이 없으면 검증(-83)이 후보
 * {A}로 지문을 남긴 뒤 같은 REVIEWING 신고에 후보 B(비활성 규칙 또는 미확정 태그)가 추가돼도 rules/tags 축(전역 활성/확정 집합)은 그대로라 지문이 안 바뀐다 — 확정(-84)이 새
 * 후보를 보지 못한 옛 검증 실행으로 통과해 부분 검증만 확정되는 stale 경합이 생긴다(F-13 4). 후보가 추가/제거되면 이 축이 바뀌어 재검증을 강제한다.
 *
 * <p>{@link #compute(long)} 은 이 두 축도 재조회한다 — -84 확정의 "현재 상태" 조회(어떤 검증 실행도 거치지 않은 시점)에 맞다. -83 검증은 대신
 * {@link #compute(long, List, List)} 를 써서 이 두 축을 <b>실제로 flip 한 로드된 후보 집합</b>에서 낸다 (S15P21A501-83 stale 확정 경합 수정) — 그래야
 * recorded fingerprint 가 검증이 실제로 검색한 집합과 항상 같다.
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
        return commonAxes(feedbackId)
                + ";pending_rules="
                + joinedIds(
                        "SELECT search_rule_id FROM npick.search_rule "
                                + "WHERE source_feedback_id = :feedbackId AND active = false ORDER BY search_rule_id",
                        feedbackId)
                + ";pending_tags="
                + joinedIds(
                        "SELECT evidence_id FROM npick.tag_evidence "
                                + "WHERE source_feedback_id = :feedbackId AND source = 'reviewer_feedback' "
                                + "AND confirmed = false ORDER BY evidence_id",
                        feedbackId);
    }

    /**
     * -83 검증 전용 오버로드(S15P21A501-83 경합 수정). {@code pending_rules}·{@code pending_tags} 축을 재조회하지 않고 <b>이미 로드해 flip 한 후보
     * 집합</b>에서 낸다. {@code VerificationSearchService.verify} 는 대기 후보를 한 번만 읽어(candidatesPort.load) 그 값으로 flip 하는데, 이 축을
     * 재조회로 두면 그 사이 커밋된 새 후보가 지문에는 잡히고 flip·검색에는 빠져 recorded fingerprint != 실제 검색한 집합이 된다 — 확정(-84)의 드리프트 가드가 그 stale
     * 검증을 통과시킨다. 인자로 받은 id 를 그대로 정본으로 삼아 recorded == flipped 를 코드로 강제한다. 다른 네 축(rules·tags· config·run)은 여전히 재조회다 — 이
     * 신고 밖의 전역 상태이므로 로드 시점 스냅샷이 없다.
     *
     * <p>{@code common} 모듈은 다른 모듈에 의존하지 않는다(순환 금지, {@code ModuleBoundaryArchitectureTest}) — 그래서
     * {@code search.PendingCandidates} 타입이 아니라 순수 id 리스트를 받는다.
     */
    public String compute(long feedbackId, List<Long> loadedPendingRuleIds, List<Long> loadedPendingTagEvidenceIds) {
        return commonAxes(feedbackId)
                + ";pending_rules=" + joinedIds(loadedPendingRuleIds)
                + ";pending_tags=" + joinedIds(loadedPendingTagEvidenceIds);
    }

    private String commonAxes(long feedbackId) {
        return "rules="
                + joinedIds(
                        "SELECT search_rule_id FROM npick.search_rule " + "WHERE active = true ORDER BY search_rule_id")
                + ";tags="
                + joinedIds("SELECT evidence_id FROM npick.tag_evidence "
                        + "WHERE source = 'reviewer_feedback' AND confirmed = true ORDER BY evidence_id")
                + ";config=" + configVersion.currentConfigVersion()
                + ";run="
                + joinedIds(
                        "SELECT DISTINCT c.active_pipeline_run_id FROM npick.feedback f "
                                + "JOIN npick.search_result sr ON sr.search_result_id = f.search_result_id "
                                + "JOIN npick.scene s ON s.scene_id = sr.scene_id "
                                + "JOIN npick.clip c ON c.clip_id = s.clip_id "
                                + "WHERE f.feedback_id = :feedbackId AND c.active_pipeline_run_id IS NOT NULL "
                                + "ORDER BY c.active_pipeline_run_id",
                        feedbackId);
    }

    /** {@code compute(feedbackId)} 재조회 축과 같은 정렬 오름차순·콤마 join 정본 포맷을 로드된 id 리스트에도 맞춘다. */
    private String joinedIds(List<Long> ids) {
        return ids.stream()
                .sorted(Comparator.naturalOrder())
                .map(String::valueOf)
                .collect(Collectors.joining(","));
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
        return rows.stream()
                .map(v -> ((Number) v).longValue())
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
