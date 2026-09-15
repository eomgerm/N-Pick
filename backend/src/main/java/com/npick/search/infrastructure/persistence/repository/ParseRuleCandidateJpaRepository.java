package com.npick.search.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.npick.search.infrastructure.persistence.entity.SearchRuleJpaEntity;

/**
 * patch_parse 후보 쓰기. 읽기 전용 {@link SearchRuleJpaRepository}(-49)를 건드리지 않고 별도 인터페이스로 둔다.
 *
 * <p>{@link SearchRuleJpaEntity} 는 5칸짜리 읽기 전용이라 {@code INSERT} 에 쓸 수 없다. {@code query_fingerprint} 등 NOT NULL 이지만
 * patch_parse 판정에 쓰지 않는 칸은 sentinel 로 채운다 — 매칭은 지문이 아니라 {@code condition_json} 으로 한다 (baseline 주석·F-05). 그래서 native
 * {@code INSERT} 다.
 */
public interface ParseRuleCandidateJpaRepository extends JpaRepository<SearchRuleJpaEntity, Long> {

    @Modifying(flushAutomatically = true)
    @Query(
            value = "INSERT INTO search_rule (search_rule_id, query_fingerprint, normalized_query, "
                    + "normalized_filters_json, normalization_version, action, source_feedback_id, active, "
                    + "created_at, updated_at, condition_json, patch_json, replaces_rule_id, request_key) "
                    + "VALUES (:id, '', '', '{}', '', 'patch_parse', :feedbackId, false, :now, :now, "
                    + "CAST(:condition AS jsonb), CAST(:patch AS jsonb), :replaces, :requestKey) "
                    + "ON CONFLICT (source_feedback_id, request_key) DO NOTHING",
            nativeQuery = true)
    int insertCandidate(
            @Param("id") long id,
            @Param("feedbackId") long sourceFeedbackId,
            @Param("condition") String conditionJson,
            @Param("patch") String patchJson,
            @Param("replaces") Long replacesRuleId,
            @Param("requestKey") String requestKey,
            @Param("now") Instant now);

    @Query(
            value = "SELECT search_rule_id FROM search_rule "
                    + "WHERE source_feedback_id = :feedbackId AND request_key = :requestKey",
            nativeQuery = true)
    List<Long> findIds(@Param("feedbackId") long sourceFeedbackId, @Param("requestKey") String requestKey);

    /** 교체 대상이 켜져 있는 patch_parse 규칙인지. 검증 조합(활성 규칙 − R1 + R2)의 R1 이 실제로 그 자리에 있는지 본다. */
    @Query(
            value = "SELECT count(*) > 0 FROM search_rule "
                    + "WHERE search_rule_id = :id AND action = 'patch_parse' AND active = true",
            nativeQuery = true)
    boolean existsActivePatchParse(@Param("id") long searchRuleId);
}
