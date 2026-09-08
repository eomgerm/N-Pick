package com.npick.feedback.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
// 동시성 검증이라 커밋된 상태를 다른 스레드가 봐야 하므로 @DataJpaTest 기본 롤백을 끈다(NOT_SUPPORTED).
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
class FeedbackClaimConcurrencyDbTest {

    private static final long FEEDBACK_ID = 8901L;
    private static final long REVIEWER_A_ID = 8002L;
    private static final long REVIEWER_B_ID = 8003L;

    @Autowired
    private FeedbackRepositoryAdapter repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        jdbcTemplate.update("DELETE FROM npick.search_result WHERE search_result_id = ?", 8801L);
        jdbcTemplate.update("DELETE FROM npick.search_execution WHERE search_execution_id = ?", 8701L);
        jdbcTemplate.update("DELETE FROM npick.scene WHERE scene_id = ?", 8301L);
        jdbcTemplate.update("DELETE FROM npick.pipeline_run WHERE pipeline_run_id = ?", 8201L);
        jdbcTemplate.update("DELETE FROM npick.clip WHERE clip_id = ?", 8101L);
        jdbcTemplate.update(
                "DELETE FROM npick.member WHERE member_id IN (?, ?, ?)", 8001L, REVIEWER_A_ID, REVIEWER_B_ID);
    }

    @Test
    @DisplayName("open 상태의 동일 신고를 두 검수자가 동시에 claim하면 정확히 한 건만 성공한다(CAS 실증)")
    void onlyOneOfTwoConcurrentClaimsSucceeds() throws Exception {
        seed();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Integer> claimAsReviewerA = claimTask(REVIEWER_A_ID, ready, start);
        Callable<Integer> claimAsReviewerB = claimTask(REVIEWER_B_ID, ready, start);

        Future<Integer> resultA = executor.submit(claimAsReviewerA);
        Future<Integer> resultB = executor.submit(claimAsReviewerB);

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        int claimedA = resultA.get(5, TimeUnit.SECONDS);
        int claimedB = resultB.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(List.of(claimedA, claimedB)).containsExactlyInAnyOrder(0, 1);
        assertThat(claimedA + claimedB).isEqualTo(1);

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM npick.feedback WHERE feedback_id = ?", String.class, FEEDBACK_ID);
        assertThat(finalStatus).isEqualTo("REVIEWING");
    }

    private Callable<Integer> claimTask(long reviewerId, CountDownLatch ready, CountDownLatch start) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return transactionTemplate.execute(status -> repository.claim(FEEDBACK_ID, reviewerId, Instant.now()));
        };
    }

    private void seed() {
        jdbcTemplate.update(
                "INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'hash', ?, ?, now(), now())",
                8001L,
                "editor-test-8001",
                "편집기자8001",
                "editor");
        jdbcTemplate.update(
                "INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'hash', ?, ?, now(), now())",
                REVIEWER_A_ID,
                "reviewer-test-8002",
                "검수자8002",
                "reviewer");
        jdbcTemplate.update(
                "INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'hash', ?, ?, now(), now())",
                REVIEWER_B_ID,
                "reviewer-test-8003",
                "검수자8003",
                "reviewer");
        jdbcTemplate.update(
                "INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source, "
                        + "registered_by_id, created_at, updated_at) "
                        + "VALUES (?, 'broadcast', 'clips/8101/original', repeat('a', 64), 'none', ?, now(), now())",
                8101L,
                8001L);
        jdbcTemplate.update(
                "INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status, "
                        + "stage_states_json, created_at, updated_at) "
                        + "VALUES (?, ?, 1, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())",
                8201L,
                8101L);
        jdbcTemplate.update(
                "INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, shot_type, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0, 1000, 'b_roll', now(), now())",
                8301L,
                8101L,
                8201L);
        jdbcTemplate.update(
                "INSERT INTO npick.search_execution (search_execution_id, searched_by_id, query_text, "
                        + "normalized_query, explicit_filters_json, normalized_filters_json, query_fingerprint, "
                        + "normalization_version, execution_type, status, degraded_reasons_json, "
                        + "applied_excludes_json, search_config_json, config_version, created_at, updated_at) "
                        + "VALUES (?, ?, '테스트 질의', '테스트 질의', '{}'::jsonb, '{}'::jsonb, 'fp-8701', 'v1', "
                        + "'original', 'succeeded', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb, 'cfg-v1', now(), now())",
                8701L,
                8001L);
        jdbcTemplate.update(
                "INSERT INTO npick.search_result (search_result_id, search_execution_id, scene_id, result_rank, "
                        + "explain_json) VALUES (?, ?, ?, 1, '{\"score\":1}'::jsonb)",
                8801L,
                8701L,
                8301L);
        jdbcTemplate.update(
                "INSERT INTO npick.feedback (feedback_id, search_result_id, created_by_id, status, created_at, "
                        + "updated_at) VALUES (?, ?, ?, 'OPEN', now(), now())",
                FEEDBACK_ID,
                8801L,
                8001L);
    }
}
