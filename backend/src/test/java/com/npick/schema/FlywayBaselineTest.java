package com.npick.schema;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * baseline 마이그레이션이 빈 DB 에 그대로 적용되는지 검증한다. Testcontainers 가 띄운 컨테이너 안에 이 테스트 전용 DB 를 새로 만들어 쓴다.
 *
 * <p>공유 DB({@link NpickPostgres#datasource})를 쓰지 않는 이유: 이 테스트는 「‘화재’로 검색되는 scene 이 1건」처럼 테이블 전체를 세는 단언을 한다. 다른 테스트가 커밋한
 * 행이 보이면 깨진다. 또 migrate 가 정확히 1건 실행되는 것을 확인하려면 DB 가 비어 있어야 한다.
 */
class FlywayBaselineTest {
    private static final String url = NpickPostgres.freshDatabase("npick_baseline");
    private static final String user = NpickPostgres.username();
    private static final String password = NpickPostgres.password();

    private static Flyway flyway;
    private Connection connection;

    @BeforeAll
    static void migrateEmptyDatabase() {
        // 기존 npick 객체가 있는지 확인하던 안전장치는 없앤다. 방금 만든 일회용 DB 라 실제 개발·운영 DB 를 가리킬 방법이 없다.
        // NpickPostgres.migrate 를 쓰지 않는다. 적용 건수와 재실행 무변경을 이 테스트가 직접 단언해야 한다.
        flyway = Flyway.configure()
                .dataSource(url, user, password)
                .defaultSchema("npick")
                .schemas("npick")
                .createSchemas(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    }

    @BeforeEach
    void openTransaction() throws Exception {
        connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(false);
        execute("SET LOCAL search_path = npick, public");
    }

    @AfterEach
    void rollbackFixture() throws Exception {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void migrationIsValidAndReexecutionIsNoOp() throws Exception {
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(number("SELECT count(*) FROM flyway_schema_history WHERE version = '20260907092019' AND success"))
                .isEqualTo(1);
    }

    @Test
    void columnsTypesNullabilityDefaultsAndCommentsMatchFinalErd() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod),
                               a.attnotnull, coalesce(col_description(c.oid, a.attnum), ''), a.atthasdef
                        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        JOIN pg_attribute a ON a.attrelid = c.oid
                        WHERE n.nspname = 'npick' AND c.relkind = 'r'
                          AND c.relname <> 'flyway_schema_history' AND a.attnum > 0 AND NOT a.attisdropped
                        ORDER BY c.relname, a.attnum
                        """)) {
            while (rows.next()) {
                assertThat(rows.getBoolean(6)).as("ERD에 없는 기본값").isFalse();
                actual.put(
                        rows.getString(1) + "\t" + rows.getString(2),
                        rows.getString(3) + "\t" + rows.getBoolean(4) + "\t" + rows.getString(5));
            }
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : resource("schema/final-erd-columns.tsv").strip().split("\n")) {
            String[] parts = line.split("\t", 5);
            // ERD의 PostgreSQL 별칭과 catalog의 정식 타입명은 같은 타입이다.
            String type = parts[2].replace("timestamptz", "timestamp with time zone");
            expected.put(parts[0] + "\t" + parts[1], type + "\t" + parts[3] + "\t" + parts[4]);
        }
        assertThat(actual).hasSize(134).isEqualTo(expected);
        assertThat(number("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='npick' AND c.relkind='r' AND c.relname<>'flyway_schema_history'
                """)).isEqualTo(13);
        assertThat(number("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='npick' AND c.relkind='r' AND c.relname<>'flyway_schema_history'
                  AND obj_description(c.oid, 'pg_class') IS NOT NULL
                """)).isEqualTo(13);
    }

    @Test
    void allForeignKeysMatchErdAndRestrictDeletion() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT child.relname, ca.attname, parent.relname, pa.attname, f.confdeltype
                        FROM pg_constraint f
                        JOIN pg_class child ON child.oid=f.conrelid
                        JOIN pg_namespace n ON n.oid=child.relnamespace
                        JOIN pg_class parent ON parent.oid=f.confrelid
                        JOIN pg_attribute ca ON ca.attrelid=child.oid AND ca.attnum=f.conkey[1]
                        JOIN pg_attribute pa ON pa.attrelid=parent.oid AND pa.attnum=f.confkey[1]
                        WHERE f.contype='f' AND n.nspname='npick'
                        """)) {
            while (rows.next()) {
                actual.put(rows.getString(1) + "." + rows.getString(2), rows.getString(3) + "." + rows.getString(4));
                assertThat(rows.getString(5)).isEqualTo("r");
            }
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : resource("schema/final-erd-fks.tsv").strip().split("\n")) {
            String[] parts = line.split("\t");
            expected.put(parts[0], parts[1]);
        }
        assertThat(actual).hasSize(23).isEqualTo(expected);
        fixture();
        rejects("DELETE FROM scene WHERE scene_id=30", "23001");
        rejects("UPDATE tagging SET tag_id=99999 WHERE tagging_id=70", "23503");
    }

    @Test
    void uniquenessAndScalarConstraintsRejectBadData() throws Exception {
        fixture();
        rejects("UPDATE member SET login_id='editor' WHERE member_id=2", "23505");
        rejects("INSERT INTO tagging VALUES (72,10,NULL,60,CURRENT_TIMESTAMP)", "23505");
        rejects("INSERT INTO tagging VALUES (73,10,30,60,CURRENT_TIMESTAMP)", "23505");
        rejects(
                "INSERT INTO feedback (feedback_id,search_result_id,created_by_id,status,created_at,updated_at) VALUES (111,100,1,'open',now(),now())",
                "23505");
        rejects("UPDATE clip SET content_hash=repeat('a',64) WHERE clip_id=11", "23505");
        execute("UPDATE clip SET deleted_at=now() WHERE clip_id=11");
        execute("UPDATE clip SET content_hash=repeat('a',64) WHERE clip_id=11");
        rejects("UPDATE scene SET end_time_ms=start_time_ms WHERE scene_id=30", "23514");
        rejects("UPDATE ocr_observation SET confidence=1.1 WHERE ocr_observation_id=50", "23514");
        rejects("UPDATE search_result SET result_rank=0 WHERE search_result_id=100", "23514");
        rejects("INSERT INTO tag VALUES (62,'filmed_date','2026-02-30','bad')", "23514");
        rejects("INSERT INTO tag VALUES (62,'broadcast_date','2026-2-03','bad')", "23514");
        execute("INSERT INTO tag VALUES (62,'filmed_date','2024-02-29','윤일')");
    }

    @Test
    void ruleAndEvidenceShapesPreserveBothCorrectionTracks() throws Exception {
        fixture();
        rejects("UPDATE search_rule SET action='pin_parse' WHERE search_rule_id=120", "23514");
        rejects("UPDATE search_rule SET patch_json=NULL WHERE search_rule_id=120", "23514");
        rejects("UPDATE tag_evidence SET verification_status='withdrawn' WHERE evidence_id=80", "23514");
        rejects("UPDATE tag_evidence SET source='reviewer_feedback' WHERE evidence_id=80", "23514");
        execute("INSERT INTO tag_evidence VALUES (81,70,'reviewer_feedback',NULL,'rejected',NULL,NULL,now(),110)");
        execute("INSERT INTO tag_evidence VALUES (82,70,'reviewer_feedback',NULL,'withdrawn',NULL,NULL,now(),110)");
        rejects("UPDATE tag_evidence SET confidence=1.0 WHERE evidence_id=81", "23514");
        execute(
                "INSERT INTO search_rule SELECT 121,query_fingerprint,normalized_query,normalized_filters_json,normalization_version,action,target_scene_id,source_feedback_id,true,now(),now(),condition_json,patch_json FROM search_rule WHERE search_rule_id=120");
        execute("UPDATE search_rule SET active=true WHERE search_rule_id=120");
        execute(
                "INSERT INTO search_rule SELECT 122,query_fingerprint,normalized_query,normalized_filters_json,normalization_version,'exclude_scene',30,source_feedback_id,false,now(),now(),NULL,NULL FROM search_rule WHERE search_rule_id=120");
        rejects("UPDATE search_rule SET target_scene_id=NULL WHERE search_rule_id=122", "23514");
        rejects("UPDATE search_execution SET verification_context_json='{}' WHERE search_execution_id=90", "23514");
    }

    @Test
    void bm25AndVectorSearchWorkWithUncommittedWrites() throws Exception {
        fixture();
        assertThat(number("SELECT count(*) FROM pg_extension WHERE extname IN ('vector','pg_search')"))
                .isEqualTo(2);
        assertThat(number("SELECT count(*) FROM scene WHERE caption_tokens @@@ '화재'"))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM ocr_observation WHERE tokens @@@ '공장'"))
                .isEqualTo(1);
        assertThat(number("SELECT vector_dims(embedding) FROM scene WHERE scene_id=30"))
                .isEqualTo(1024);
        try (var stmt = connection.createStatement();
                var rows = stmt.executeQuery("SELECT embedding <-> embedding FROM scene WHERE scene_id=30")) {
            rows.next();
            assertThat(rows.getDouble(1)).isZero();
        }
        rejects("UPDATE scene SET embedding='[1,2,3]' WHERE scene_id=30", "22000");
        var savepoint = connection.setSavepoint();
        execute("UPDATE scene SET caption_tokens='검증후보' WHERE scene_id=30");
        assertThat(number("SELECT count(*) FROM scene WHERE caption_tokens @@@ '검증후보'"))
                .isEqualTo(1);
        connection.rollback(savepoint);
        assertThat(number("SELECT count(*) FROM scene WHERE caption_tokens @@@ '검증후보'"))
                .isZero();
    }

    @Test
    void candidateRollbackAndSeparateVerificationRecordArePossible() throws Exception {
        fixture();
        var candidate = connection.setSavepoint();
        execute("INSERT INTO tag_evidence VALUES (81,70,'reviewer_feedback',NULL,'verified',NULL,NULL,now(),110)");
        execute(
                "INSERT INTO search_rule SELECT 123,query_fingerprint,normalized_query,normalized_filters_json,normalization_version,action,target_scene_id,source_feedback_id,false,now(),now(),condition_json,patch_json FROM search_rule WHERE search_rule_id=120");
        assertThat(number("SELECT count(*) FROM tag_evidence WHERE evidence_id=81"))
                .isEqualTo(1);
        connection.rollback(candidate);
        assertThat(number("SELECT count(*) FROM search_rule WHERE search_rule_id=123"))
                .isZero();
        execute("""
                INSERT INTO search_execution
                SELECT 91,searched_by_id,query_text,normalized_query,explicit_filters_json,normalized_filters_json,
                       query_fingerprint,normalization_version,'replay',110,status,degraded_reasons_json,error_code,
                       parse_source,parsed_query_json,parser_version,parse_ms,candidates_json,filtered_json,
                       applied_excludes_json,search_config_json,config_version,execution_ms,now(),now(),
                       resolver_output_json,'[]','{"candidate_body":{"example":true}}'
                FROM search_execution WHERE search_execution_id=90
                """);
        execute("UPDATE feedback SET verified_by_execution_id=91,created_rule_id=120 WHERE feedback_id=110");
        assertThat(number("SELECT count(*) FROM search_execution WHERE replay_of_feedback_id=110"))
                .isEqualTo(1);
    }

    private void fixture() throws Exception {
        execute(resource("schema/fixture.sql"));
    }

    private static String resource(String path) throws Exception {
        try (var stream = FlywayBaselineTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void rejects(String sql, String state) throws Exception {
        var savepoint = connection.setSavepoint();
        try {
            SQLException failure = assertThrows(SQLException.class, () -> execute(sql));
            assertThat(failure.getSQLState()).isEqualTo(state);
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
