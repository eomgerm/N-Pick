package com.npick.feedback.infrastructure;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.npick.feedback.infrastructure.persistence.mapper.FeedbackPersistenceMapper;
import com.npick.feedback.infrastructure.persistence.repository.FeedbackRepositoryAdapter;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 컨테이너 대상 통합 테스트. NPICK_FEEDBACK_DB_TEST_URL 이 없으면 스킵된다.
// resolve()의 CAS 조건부 UPDATE를 실증한다: 커밋된 행 상태를 JdbcTemplate로 직접 검증하므로
// @DataJpaTest 기본 롤백을 끄고(NOT_SUPPORTED) @AfterEach에서 수동으로 FK 체인을 정리한다.
@DataJpaTest(
        properties = {
            "spring.autoconfigure.exclude=",
            "spring.flyway.enabled=true",
            "spring.flyway.schemas=npick",
            "spring.flyway.default-schema=npick",
            "spring.flyway.create-schemas=true",
            "spring.jpa.properties.hibernate.default_schema=npick"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FeedbackRepositoryAdapter.class, FeedbackPersistenceMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "NPICK_FEEDBACK_DB_TEST_URL", matches = ".+")
class FeedbackResolveDbTest {

    private static final long FEEDBACK_ID = 9901L;
    private static final long EDITOR_ID = 9001L;
    private static final long REVIEWER_A_ID = 9002L;
    private static final long REVIEWER_B_ID = 9003L;
    private static final long CLIP_ID = 9101L;
    private static final long PIPELINE_RUN_ID = 9201L;
    private static final long SCENE_ID = 9301L;
    private static final long SEARCH_EXECUTION_ID = 9701L;
    private static final long SEARCH_RESULT_ID = 9801L;

    @Autowired
    private FeedbackRepositoryAdapter repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // resolve()는 @Modifying 쿼리라 활성 트랜잭션이 필요하다. 클래스가 NOT_SUPPORTED라 각 호출을 트랜잭션으로 감싼다(claim 동시성 테스트와 동일).
    private int resolveTx(
            long feedbackId,
            long reviewerId,
            String resolution,
            String note,
            String newStatus,
            Instant closedAt,
            Instant now) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> repository.resolve(feedbackId, reviewerId, resolution, note, newStatus, closedAt, now));
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("NPICK_FEEDBACK_DB_TEST_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("NPICK_FEEDBACK_DB_TEST_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("NPICK_FEEDBACK_DB_TEST_PASSWORD"));
        // 테스트 DB 계정(npick_test)은 "$user" 스키마 관례를 안 따르므로 세션 search_path를 직접 건다.
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO npick, public");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM npick.feedback WHERE feedback_id = ?", FEEDBACK_ID);
        jdbcTemplate.update("DELETE FROM npick.search_result WHERE search_result_id = ?", SEARCH_RESULT_ID);
        jdbcTemplate.update("DELETE FROM npick.search_execution WHERE search_execution_id = ?", SEARCH_EXECUTION_ID);
        jdbcTemplate.update("DELETE FROM npick.scene WHERE scene_id = ?", SCENE_ID);
        jdbcTemplate.update("DELETE FROM npick.pipeline_run WHERE pipeline_run_id = ?", PIPELINE_RUN_ID);
        jdbcTemplate.update("DELETE FROM npick.clip WHERE clip_id = ?", CLIP_ID);
        jdbcTemplate.update(
                "DELETE FROM npick.member WHERE member_id IN (?, ?, ?)", EDITOR_ID, REVIEWER_A_ID, REVIEWER_B_ID);
    }

    @Test
    @DisplayName("REVIEWING 중 교정 resolution을 적으면 1건 갱신되고 상태는 REVIEWING을 유지한다")
    void correctionKeepsReviewing() {
        seed();

        int affected = resolveTx(FEEDBACK_ID, REVIEWER_A_ID, "patch_parse", null, "REVIEWING", null, Instant.now());

        assertThat(affected).isEqualTo(1);
        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo("REVIEWING");
        assertThat(row.get("resolution")).isEqualTo("patch_parse");
        assertThat(row.get("closed_at")).isNull();
    }

    @Test
    @DisplayName("REVIEWING 중에는 resolution을 여러 번 덮어쓸 수 있다(A/overwrite 의미)")
    void overwriteWhileReviewing() {
        seed();

        int first = resolveTx(FEEDBACK_ID, REVIEWER_A_ID, "patch_parse", null, "REVIEWING", null, Instant.now());
        int second = resolveTx(FEEDBACK_ID, REVIEWER_A_ID, "tag_correction", null, "REVIEWING", null, Instant.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        Map<String, Object> row = row();
        assertThat(row.get("resolution")).isEqualTo("tag_correction");
        assertThat(row.get("status")).isEqualTo("REVIEWING");
    }

    @Test
    @DisplayName("종결 resolution은 상태를 CLOSED로 바꾸고 closed_at을 채운다")
    void terminalClosesFeedback() {
        seed();

        Instant now = Instant.now();
        int affected = resolveTx(FEEDBACK_ID, REVIEWER_A_ID, "no_action", "문제 없음", "CLOSED", now, now);

        assertThat(affected).isEqualTo(1);
        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo("CLOSED");
        assertThat(row.get("resolution")).isEqualTo("no_action");
        assertThat(row.get("closed_at")).isNotNull();
    }

    @Test
    @DisplayName("claim하지 않은 다른 검수자의 resolve는 0건이며 행은 변하지 않는다")
    void wrongReviewerLocked() {
        seed();

        Instant now = Instant.now();
        int affected = resolveTx(FEEDBACK_ID, REVIEWER_B_ID, "no_action", "x", "CLOSED", now, now);

        assertThat(affected).isEqualTo(0);
        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo("REVIEWING");
        assertThat(row.get("resolution")).isNull();
    }

    @Test
    @DisplayName("이미 CLOSED된 신고는 status 가드에 막혀 재-resolve가 0건이다")
    void closedIsLocked() {
        seed();

        Instant now = Instant.now();
        int closed = resolveTx(FEEDBACK_ID, REVIEWER_A_ID, "no_action", "종결", "CLOSED", now, now);
        int reopen = resolveTx(FEEDBACK_ID, REVIEWER_A_ID, "patch_parse", null, "REVIEWING", null, Instant.now());

        assertThat(closed).isEqualTo(1);
        assertThat(reopen).isEqualTo(0);
        Map<String, Object> row = row();
        assertThat(row.get("status")).isEqualTo("CLOSED");
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap(
                "SELECT status, resolution, closed_at FROM npick.feedback WHERE feedback_id = ?", FEEDBACK_ID);
    }

    private void seed() {
        jdbcTemplate.update(
                "INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'hash', ?, ?, now(), now())",
                EDITOR_ID,
                "editor-test-9001",
                "편집기자9001",
                "editor");
        jdbcTemplate.update(
                "INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'hash', ?, ?, now(), now())",
                REVIEWER_A_ID,
                "reviewer-test-9002",
                "검수자9002",
                "reviewer");
        jdbcTemplate.update(
                "INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'hash', ?, ?, now(), now())",
                REVIEWER_B_ID,
                "reviewer-test-9003",
                "검수자9003",
                "reviewer");
        jdbcTemplate.update(
                "INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source, "
                        + "registered_by_id, created_at, updated_at) "
                        + "VALUES (?, 'broadcast', 'clips/9101/original', repeat('a', 64), 'none', ?, now(), now())",
                CLIP_ID,
                EDITOR_ID);
        jdbcTemplate.update(
                "INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status, "
                        + "stage_states_json, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())",
                PIPELINE_RUN_ID,
                CLIP_ID);
        jdbcTemplate.update(
                "INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, 1000, 'b_roll', now(), now())",
                SCENE_ID,
                CLIP_ID,
                PIPELINE_RUN_ID);
        jdbcTemplate.update(
                "INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, "
                        + "normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint, "
                        + "normalization_version, execution_type, status, degraded_reasons_json, "
                        + "applied_excludes_json, search_config_json, config_version, created_at, updated_at) "
                        + "VALUES (?, ?, '테스트 질의', '테스트 질의', '{}'::jsonb, '{}'::jsonb, 'fp-9701', 'v1', "
                        + "'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', now(), now())",
                SEARCH_EXECUTION_ID,
                EDITOR_ID);
        jdbcTemplate.update(
                "INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank, "
                        + "explain_json) VALUES (?, ?, ?, 1, '{\"score\":1}'::jsonb)",
                SEARCH_RESULT_ID,
                SEARCH_EXECUTION_ID,
                SCENE_ID);
        jdbcTemplate.update(
                "INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, "
                        + "reviewed_by_id, review_started_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'REVIEWING', ?, now(), now(), now())",
                FEEDBACK_ID,
                SEARCH_RESULT_ID,
                EDITOR_ID,
                REVIEWER_A_ID);
    }
}
