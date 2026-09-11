package com.npick.schema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.support.NpickPostgres;
import com.npick.tag.domain.model.TagMatchValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code ck_tag_match_value_no_whitespace} 가 정규화를 안 거친 저장을 막는지 검증한다 (S15P21A501-169).
 *
 * <p>{@code FlywayBaselineTest} 는 발행된 ERD 스냅샷({@code 20260907092019})에 자신을 고정해 두었으므로 이후 기술 마이그레이션은 여기처럼 따로 검증한다.
 *
 * <p>전용 DB 를 받는다. 공유 DB 에 마이그레이션을 걸면 실행 순서에 따라 다른 클래스의 전역 단언이 깨진다.
 */
class TagMatchValueConstraintTest {

    private static String url;

    private Connection connection;

    @BeforeAll
    static void migrateOwnDatabase() {
        url = NpickPostgres.freshDatabase("npick_tag_match_value");
        NpickPostgres.migrate(url);
    }

    @BeforeEach
    void openTransaction() throws Exception {
        connection = DriverManager.getConnection(url, NpickPostgres.username(), NpickPostgres.password());
        connection.setAutoCommit(false);
        execute("SET LOCAL search_path = npick, public");
    }

    @AfterEach
    void rollback() throws Exception {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("공백이 있는 match_value 를 거부한다 - 함수를 안 거친 저장 경로가 생겨도 DB 가 잡는다")
    void rejectsWhitespaceInMatchValue() throws Exception {
        rejects(insert("event", "이태원 참사"));
        rejects(insert("event", "이태원\t참사"));
        rejects(insert("keyword", " 화재"));
    }

    @Test
    @DisplayName("폭 없는 문자가 있는 match_value 를 거부한다 - [[:space:]] 에 안 걸리는 것들이다")
    void rejectsZeroWidthInMatchValue() throws Exception {
        // ZWSP 는 이름과 달리 유니코드 공백이 아니다. 이게 통과하면 화면상 같은 값이 두 행이 된다.
        rejects(insert("event", "이태원" + Character.toString(0x200B) + "참사"));
        rejects(insert("event", "이태원" + Character.toString(0xFEFF) + "참사"));
        rejects(insert("keyword", "화재" + Character.toString(0x00AD)));
    }

    @Test
    @DisplayName("빈 match_value 를 거부한다 - normalize() 가 널·보이지 않는 문자만 있는 입력을 접어 내는 값이다")
    void rejectsEmptyMatchValue() throws Exception {
        // 저장 경로가 빈 값 검사를 빠뜨리면 이름 없는 태그 한 행이 생기고, UNIQUE 때문에 이후 모든 빈 값이 그 행에 붙는다.
        // 리터럴을 쓴다. normalize() 로 만들면 그 함수가 회귀했을 때 이 테스트가 엉뚱한 규칙에 걸려 초록으로 남는다.
        rejects(insert("event", ""));
    }

    @Test
    @DisplayName("normalize() 를 거친 값은 통과한다 - 표시값의 띄어쓰기는 name 이 그대로 들고 있다")
    void acceptsNormalizedValue() throws Exception {
        execute(insert("event", TagMatchValue.normalize("이태원 참사")));

        assertThat(number("SELECT count(*) FROM tag WHERE tag_id=63 AND match_value='이태원참사'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("날짜 태그는 영향받지 않는다 - ck_tag_date 의 YYYY-MM-DD 에 공백이 없다")
    void keepsDateTagsValid() throws Exception {
        execute(
                "INSERT INTO tag (tag_id, tag_type, match_value, name) VALUES (64,'filmed_date','2026-03-15','2026-03-15')");

        assertThat(number("SELECT count(*) FROM tag WHERE tag_id=64")).isEqualTo(1);
    }

    /** 컬럼 목록을 적는다 — tag 에 컬럼이 하나 늘어도 이 테스트가 깨지지 않는다. name 은 표시값이라 정규화 대상이 아니다. */
    private static String insert(String tagType, String matchValue) {
        return "INSERT INTO tag (tag_id, tag_type, match_value, name) VALUES (63,'" + tagType + "','" + matchValue
                + "','표시값')";
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

    /** CHECK 위반은 SQLState 23514 다. 사보포인트로 되돌려 한 트랜잭션에서 여러 건을 확인한다. */
    private void rejects(String sql) throws Exception {
        var savepoint = connection.setSavepoint();
        try {
            SQLException failure = assertThrows(SQLException.class, () -> execute(sql));
            assertThat(failure.getSQLState()).isEqualTo("23514");
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
