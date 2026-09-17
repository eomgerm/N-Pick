package com.npick.feedback.infrastructure.persistence.query;

import java.util.List;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.feedback.application.port.CurrentCorrectionStatePort;

/**
 * 확정 시점의 현재 상태 지문을 계산한다 (S15P21A501-84, F-13 3·4).
 *
 * <p><b>v1 지문 범위:</b> 「전체 활성 규칙 집합」(F-13 이 명시)과 「확정된 검수자 태그 근거 집합」을 정규 문자열로 잇는다. 검증을 만드는 쪽(S15P21A501-83)이 같은 방식으로 지문을
 * 계산해 스냅샷에 남기고, 확정은 같은지 비교만 한다. 두 계산이 같은 정의를 써야 하므로 지문 규칙을 넓히면 양쪽을 함께 고쳐야 한다.
 *
 * <p>지문은 해시하지 않고 정규 문자열 그대로 둔다 — 사람이 읽을 수 있고 재현하기 쉬워 검증·확정 양쪽에서 어긋남을 빨리 잡는다. {@code feedbackId} 는 지금 범위에 쓰지 않지만(규칙·근거
 * 집합이 전역이다) 계약에 남겨 뒷 버전이 신고 범위 지문으로 좁힐 수 있게 한다.
 */
@Repository
class CurrentCorrectionStateQueryAdapter implements CurrentCorrectionStatePort {

    private final EntityManager em;

    CurrentCorrectionStateQueryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public String currentFingerprint(long feedbackId) {
        // 「전체 활성 규칙 집합」 — action 을 가리지 않는다. patch_parse 뿐 아니라 exclude_scene 등 활성 규칙 전부가 검색 결과를
        // 바꾸므로, 검증 이후 어느 규칙이 활성/비활성으로 바뀌어도 재검증 대상이다(F-13 4). 한 종류만 넣으면 다른 종류의 규칙 변경이
        // drift 검사를 우회한다.
        return "rules="
                + joinedIds(
                        "SELECT search_rule_id FROM npick.search_rule " + "WHERE active = true ORDER BY search_rule_id")
                + ";tags="
                + joinedIds("SELECT evidence_id FROM npick.tag_evidence "
                        + "WHERE source = 'reviewer_feedback' AND confirmed = true ORDER BY evidence_id");
    }

    private String joinedIds(String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        return rows.stream()
                .map(v -> ((Number) v).longValue())
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
