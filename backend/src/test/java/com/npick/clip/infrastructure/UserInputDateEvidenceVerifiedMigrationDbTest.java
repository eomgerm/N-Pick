package com.npick.clip.infrastructure;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S15P21A501-231 보정 마이그레이션. 이미 {@code unverified} 로 저장된 등록 입력 날짜 근거만 {@code verified} 로 올리는지 본다.
 *
 * <p>Spring 을 띄우지 않는다. 검증 대상이 SQL 한 문장이고, 그 문장이 <b>이전 판 데이터</b> 위에서 돌아야 의미가 있기 때문이다. 공유 컨테이너는 이미 최신까지 올라가 있으므로 전용 DB 를
 * 받아 직전 버전까지만 올리고, 결함이 있던 모양의 행을 심은 뒤 나머지를 올린다.
 *
 * <p>{@code reviewer_feedback} 은 심지 않는다. {@code ck_evidence_review_shape} 가 사람 판단의 {@code source} 를
 * {@code reviewer_feedback} 으로 강제하므로 {@code source = 'user_input'} 조건이 구조적으로 이미 배제한다 — 행을 심어 확인할 수 있는 것이 제약 자체뿐이다.
 */
class UserInputDateEvidenceVerifiedMigrationDbTest {

    /** 이 보정 바로 앞의 마이그레이션. 여기까지만 올려 결함이 있던 시점의 스키마를 만든다. */
    private static final String BEFORE_FIX = "20260921100000";

    @Test
    @DisplayName("user_input 날짜 근거만 verified 로 올리고 추정 근거·비날짜 태그는 그대로 둔다")
    void promotesOnlyUserInputDateEvidence() throws SQLException {
        String url = NpickPostgres.freshDatabase("npick_user_input_date_backfill");
        flyway(url).target(MigrationVersion.fromVersion(BEFORE_FIX)).load().migrate();
        seedLegacyRows(url);

        flyway(url).load().migrate();

        assertThat(statusByEvidenceId(url))
                .containsEntry(1L, "verified") // 등록 입력 방송일 — 대상
                .containsEntry(2L, "verified") // 등록 입력 촬영일 — 대상
                .containsEntry(3L, "unverified") // 등록 입력이지만 날짜 태그가 아니다
                .containsEntry(4L, "unverified") // 방송일이지만 ASR 추정 근거다 (F-04)
                .containsEntry(5L, "verified"); // 이미 옳던 행. 건드리지 않아도 그대로다
    }

    /** 결함이 있던 시점의 행. {@code tagging} 은 {@code clip} 을, {@code clip} 은 {@code member} 를 요구한다. */
    private static void seedLegacyRows(String url) {
        NpickPostgres.execute(
                url,
                "INSERT INTO npick.member VALUES (1, 'backfill-test', 'test-only', '검수자', 'reviewer', now(), now())");
        NpickPostgres.execute(url, """
                INSERT INTO npick.clip VALUES
                    (1, 'broadcast', 'clips/1/original', repeat('a', 64), NULL, '보정 대상', 'none', NULL, 1, NULL, now(), now(), NULL)
                """);
        NpickPostgres.execute(url, """
                INSERT INTO npick.tag (tag_id, tag_type, match_value, name) VALUES
                    (10, 'broadcast_date', '2026-08-20', '2026-08-20'),
                    (11, 'filmed_date', '2026-08-19', '2026-08-19'),
                    (12, 'keyword', '집중호우', '집중호우'),
                    (13, 'broadcast_date', '2026-08-21', '2026-08-21')
                """);
        NpickPostgres.execute(url, """
                INSERT INTO npick.tagging (tagging_id, clip_id, scene_id, tag_id, created_at) VALUES
                    (20, 1, NULL, 10, now()),
                    (21, 1, NULL, 11, now()),
                    (22, 1, NULL, 12, now()),
                    (23, 1, NULL, 13, now())
                """);
        NpickPostgres.execute(url, """
                INSERT INTO npick.tag_evidence
                    (evidence_id, tagging_id, source, confidence, verification_status, source_ref_type, source_ref_id, created_at, source_feedback_id)
                VALUES
                    (1, 20, 'user_input', NULL, 'unverified', NULL, NULL, now(), NULL),
                    (2, 21, 'user_input', NULL, 'unverified', NULL, NULL, now(), NULL),
                    (3, 22, 'user_input', NULL, 'unverified', NULL, NULL, now(), NULL),
                    (4, 23, 'asr', 0.9000, 'unverified', NULL, NULL, now(), NULL),
                    (5, 23, 'original_metadata', NULL, 'verified', NULL, NULL, now(), NULL)
                """);
    }

    private static Map<Long, String> statusByEvidenceId(String url) throws SQLException {
        var statuses = new LinkedHashMap<Long, String>();
        try (var connection = DriverManager.getConnection(url, NpickPostgres.username(), NpickPostgres.password());
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT evidence_id, verification_status FROM npick.tag_evidence ORDER BY evidence_id")) {
            while (rows.next()) {
                statuses.put(rows.getLong(1), rows.getString(2));
            }
        }
        return statuses;
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, NpickPostgres.username(), NpickPostgres.password())
                .defaultSchema("npick")
                .schemas("npick")
                .createSchemas(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .locations("classpath:db/migration");
    }
}
