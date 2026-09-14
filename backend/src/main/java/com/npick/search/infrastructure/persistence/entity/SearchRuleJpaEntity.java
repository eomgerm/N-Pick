package com.npick.search.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code search_rule} 행.
 *
 * <p><b>읽기 전용이다.</b> 이 일감(S15P21A501-49)은 활성 규칙을 판정에 쓰기 위해 조회만 한다. 규칙을 쓰는 것은 교정 확정({@code S15P21A501-84})과 사용
 * 중단({@code -86}) 소관이고, 그때 나머지 칸을 여기 더한다.
 *
 * <p>그래서 칸이 테이블보다 적다. {@code query_fingerprint} · {@code normalized_query} 등은 {@code patch_parse} 판정에 쓰지 않는다 — 매칭은 지문
 * 일치가 아니라 {@code condition_json} 으로 AI 원본 해석을 판정하는 것이다 (F-05). {@code NOT NULL} 인 칸이 빠져 있으므로 이 엔티티로 {@code INSERT} 하면
 * 실패한다.
 */
@Entity
@Table(name = "search_rule")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchRuleJpaEntity {

    @Id
    @Column(name = "search_rule_id", nullable = false)
    private Long searchRuleId;

    /** {@code patch_parse} 또는 {@code exclude_scene}. 장면 제외는 {@code S15P21A501-58} 소관이라 이 일감은 읽지 않는다. */
    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_json", columnDefinition = "jsonb")
    private String conditionJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "patch_json", columnDefinition = "jsonb")
    private String patchJson;
}
