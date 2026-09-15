package com.npick.search.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.npick.search.infrastructure.persistence.entity.SearchRuleJpaEntity;

/**
 * 장면 제외 후보 쓰기. 읽기 전용 {@link SearchRuleJpaRepository}(-49)를 건드리지 않고 별도 인터페이스로 둔다.
 *
 * <p>exclude_scene 행은 {@code target_scene_id} 와 지문 계열 컬럼을 채우고 {@code condition_json}·{@code patch_json} 은 NULL 이다
 * (baseline {@code ck_search_rule_action_shape}). 지문은 sentinel 이 아니라 원 검색 실행값이다 — exact 매칭 키이기 때문이다.
 */
public interface SceneExcludeCandidateJpaRepository extends JpaRepository<SearchRuleJpaEntity, Long> {

    @Modifying(flushAutomatically = true)
    @Query(
            value = "INSERT INTO search_rule (search_rule_id, query_fingerprint, normalized_query, "
                    + "normalized_filters_json, normalization_version, action, target_scene_id, "
                    + "source_feedback_id, active, created_at, updated_at, request_key) "
                    + "VALUES (:id, :fingerprint, :normalizedQuery, CAST(:filters AS jsonb), :version, "
                    + "'exclude_scene', :sceneId, :feedbackId, false, :now, :now, :requestKey) "
                    // 멱등은 (신고, 장면) 부분 유니크로 건다 — exclude 후보는 장면당 하나뿐이라 request_key 가 바뀐 재시도도 중복을 만들지 않는다.
                    + "ON CONFLICT (source_feedback_id, target_scene_id) WHERE action = 'exclude_scene' DO NOTHING",
            nativeQuery = true)
    int insertCandidate(
            @Param("id") long id,
            @Param("fingerprint") String queryFingerprint,
            @Param("normalizedQuery") String normalizedQuery,
            @Param("filters") String normalizedFiltersJson,
            @Param("version") String normalizationVersion,
            @Param("sceneId") long targetSceneId,
            @Param("feedbackId") long sourceFeedbackId,
            @Param("requestKey") String requestKey,
            @Param("now") Instant now);

    @Query(
            value = "SELECT search_rule_id FROM search_rule "
                    + "WHERE source_feedback_id = :feedbackId AND target_scene_id = :sceneId "
                    + "AND action = 'exclude_scene'",
            nativeQuery = true)
    List<Long> findIdsByScene(@Param("feedbackId") long sourceFeedbackId, @Param("sceneId") long targetSceneId);
}
