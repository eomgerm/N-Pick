package com.npick.search.infrastructure.persistence.repository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.search.domain.model.SceneExcludeCandidate;
import com.npick.search.domain.repository.SceneExcludeCandidateRepository;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상. exclude_scene 후보 쓰기 어댑터(-82) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SceneExcludeCandidateRepositoryAdapter.class)
class SceneExcludeCandidateRepositoryAdapterDbTest {

    @Autowired
    private SceneExcludeCandidateRepository repository;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    private SceneExcludeCandidate candidate() {
        return new SceneExcludeCandidate(9901L, "rk-1", 9301L, "fp-9701", "제주 불꽃놀이", "{}", "v1");
    }

    @Test
    @Transactional
    @DisplayName("제외 후보를 active=false exclude_scene 행으로 저장하고 신고·요청키로 다시 찾는다")
    void savesInactiveExcludeCandidate() {
        seed();

        long id = repository.insertIfAbsent(candidate()).orElseThrow();

        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT action, active, target_scene_id, source_feedback_id, request_key, query_fingerprint, "
                                + "condition_json, patch_json FROM search_rule WHERE search_rule_id = :id")
                .setParameter("id", id)
                .getSingleResult();
        assertThat(row[0]).isEqualTo("exclude_scene");
        assertThat(row[1]).isEqualTo(false);
        assertThat(((Number) row[2]).longValue()).isEqualTo(9301L);
        assertThat(((Number) row[3]).longValue()).isEqualTo(9901L);
        assertThat(row[4]).isEqualTo("rk-1");
        assertThat(row[5]).isEqualTo("fp-9701");
        assertThat(row[6]).isNull();
        assertThat(row[7]).isNull();
        assertThat(repository.findByTargetScene(9901L, 9301L)).contains(id);
    }

    @Test
    @Transactional
    @DisplayName("같은 신고·요청키로 두 번 저장하면 두 번째는 예외 없이 empty 로 흡수된다 (ON CONFLICT)")
    void secondInsertIsAbsorbedAsEmpty() {
        seed();
        assertThat(repository.insertIfAbsent(candidate())).isPresent();

        assertThat(repository.insertIfAbsent(candidate())).isEmpty();

        Number count = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM search_rule WHERE source_feedback_id = 9901 AND request_key = 'rk-1'")
                .getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("요청키가 달라도 같은 신고·장면이면 두 번째는 흡수된다 (부분 유니크로 장면당 하나)")
    void differentRequestKeySameSceneIsAbsorbed() {
        seed();
        assertThat(repository.insertIfAbsent(candidate())).isPresent();

        // FE 가 새 Idempotency-Key 로 재시도한 상황 — 대상 장면은 그대로다.
        SceneExcludeCandidate retryWithNewKey =
                new SceneExcludeCandidate(9901L, "rk-2", 9301L, "fp-9701", "제주 불꽃놀이", "{}", "v1");
        assertThat(repository.insertIfAbsent(retryWithNewKey)).isEmpty();

        Number count = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM search_rule WHERE source_feedback_id = 9901 AND target_scene_id = 9301")
                .getSingleResult();
        assertThat(count.intValue()).isEqualTo(1);
    }

    private void seed() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자', 'editor', now(), now())");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at) VALUES (9101, 'broadcast', 'clips/9101/o',"
                + " repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (9201, 9101, 3, 'v1', 'succeeded',"
                + " '{}'::jsonb, now(), now())");
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())");
        exec("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, normalized_query,"
                + " explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,"
                + " execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,"
                + " config_version, created_at, updated_at) VALUES (9701, 9001, 'q', 'q', '{}'::jsonb, '{}'::jsonb,"
                + " 'fp-9701', 'v1', 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1',"
                + " now(), now())");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
        exec("INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, reviewed_by_id,"
                + " resolution, created_at, review_started_at, updated_at) VALUES (9901, 9801, 9001,"
                + " 'REVIEWING', 9001, 'exclude_scene', now(), now(), now())");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
