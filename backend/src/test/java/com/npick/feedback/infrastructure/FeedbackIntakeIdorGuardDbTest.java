package com.npick.feedback.infrastructure;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.infrastructure.persistence.repository.FeedbackJpaRepository;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성측 IDOR 가드 SQL 실DB 검증 (S15P21A501-209, S15P21A501-185 후속).
 *
 * <p>문의 접수는 {@code existsSearchResultSearchedBy}로 「그 검색 결과를 **본인이 실행한** 검색인지」 확인한다.
 * 단순 결과 존재가 아니라 {@code search_execution.searched_by_id} 조건이 실제로 걸리는지를 실 PostgreSQL 로 고정한다 —
 * 이 조건이 빠지면 타인 검색 결과에 자기 명의 문의를 달아 원 검색자의 질의·필터가 노출된다(IDOR).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FeedbackIntakeIdorGuardDbTest {

    private static final long OWNER = 9001;
    private static final long OTHER = 9003;
    private static final long RESULT_OF_OWNER = 9801; // 9001 이 실행한 검색(9701)의 결과

    @Autowired
    private FeedbackJpaRepository repository;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("본인이 실행한 검색의 결과면 허용한다")
    void allowsWhenResultBelongsToOwnSearch() {
        seed();

        assertThat(repository.existsSearchResultSearchedBy(RESULT_OF_OWNER, OWNER)).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("타인이 실행한 검색의 결과면 차단한다 — searched_by_id 조건이 실제로 걸린다")
    void blocksWhenResultBelongsToAnotherUsersSearch() {
        seed();

        assertThat(repository.existsSearchResultSearchedBy(RESULT_OF_OWNER, OTHER)).isFalse();
    }

    @Test
    @Transactional
    @DisplayName("존재하지 않는 검색 결과면 차단한다")
    void blocksWhenResultDoesNotExist() {
        seed();

        assertThat(repository.existsSearchResultSearchedBy(99999, OWNER)).isFalse();
    }

    private void seed() {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자9001', 'editor', now(), now())");
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9003, 'editor-9003', 'hash', '편집기자9003', 'editor', now(), now())");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,"
                + " registered_by_id, created_at, updated_at)"
                + " VALUES (9101, 'broadcast', 'clips/9101/original', repeat('a', 64), 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at)"
                + " VALUES (9201, 9101, 3, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())");
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type,"
                + " created_at, updated_at) VALUES (9301, 9101, 9201, 42000, 49000, 'b_roll', now(), now())");
        // 9001 이 실행한 검색과 그 결과
        exec("INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, normalized_query,"
                + " explicit_filters_json, normalized_filters_json, query_fingerprint, normalization_version,"
                + " execution_type, status, degraded_reasons_json, applied_excludes_json, search_config_json,"
                + " config_version, created_at, updated_at)"
                + " VALUES (9701, 9001, '테스트 질의', '테스트 질의', '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1',"
                + " 'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', now(), now())");
        exec("INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank,"
                + " explain_json) VALUES (9801, 9701, 9301, 1, '{\"score\":1}'::jsonb)");
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
