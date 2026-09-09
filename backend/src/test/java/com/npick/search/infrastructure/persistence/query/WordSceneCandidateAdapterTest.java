package com.npick.search.infrastructure.persistence.query;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.npick.common.error.BusinessException;
import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.infrastructure.config.SceneCandidateProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 pg_search 색인에 질의해 단어 검색을 검증한다 (S15P21A501-51 완료 조건).
 *
 * <p>{@code FlywayBaselineTest} 와 같은 전용 테스트 DB 환경 변수에서만 실행된다. BM25 는 pg_search 확장의 기능이라 인메모리 DB 로 대체할 수 없고, 이 테스트가 생략된
 * 것은 검색 동작 확인이 성공했다는 뜻이 아니다. 컨테이너 실행 방법은 backend README 의 마이그레이션 검증 절을 따른다.
 *
 * <p>같은 서버에 <b>별도 데이터베이스</b> 를 만들어 쓴다. {@code FlywayBaselineTest} 는 "기존 npick 객체가 하나도 없을 때만" 실행되도록 자신을 보호하는데, 이 테스트가 같은
 * 스키마에 마이그레이션을 걸면 실행 순서에 따라 그 검사가 깨진다. 순서에 의존하는 대신 서로 안 만나게 한다.
 *
 * <p>표본은 커밋하지 않고 테스트 트랜잭션에서 롤백한다. 어댑터가 커밋 전 데이터를 보게 하려고 같은 커넥션을 {@link SingleConnectionDataSource} 로 감싼다 — 색인이 정본과 같은
 * 트랜잭션에 있다는 것이 이 구성의 전제다 (FRD §11).
 */
@EnabledIfEnvironmentVariable(named = "NPICK_MIGRATION_TEST_URL", matches = ".+")
class WordSceneCandidateAdapterTest {
    private static final String DATABASE = "npick_scene_candidate_test";

    private static String url;
    private static String user;
    private static String password;

    private Connection connection;
    private SingleConnectionDataSource dataSource;

    @BeforeAll
    static void migrateOwnDatabase() throws Exception {
        String givenUrl = System.getenv("NPICK_MIGRATION_TEST_URL");
        user = System.getenv("NPICK_MIGRATION_TEST_USER");
        password = System.getenv("NPICK_MIGRATION_TEST_PASSWORD");
        assertThat(user).as("전용 테스트 DB 사용자").isNotBlank();
        assertThat(password).as("전용 테스트 DB 비밀번호").isNotBlank();
        url = replaceDatabase(givenUrl, DATABASE);
        // 앞선 실행이 중간에 끊겨 남아 있을 수 있다. 지우고 다시 만든다 — 이 이름의 DB 는 이 테스트만 쓴다.
        try (var admin = DriverManager.getConnection(givenUrl, user, password);
                var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        try (var created = DriverManager.getConnection(url, user, password);
                var statement = created.createStatement()) {
            statement.execute("CREATE SCHEMA npick");
        }
        Flyway.configure()
                .dataSource(url, user, password)
                .defaultSchema("npick")
                .schemas("npick")
                .createSchemas(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropOwnDatabase() throws Exception {
        String givenUrl = System.getenv("NPICK_MIGRATION_TEST_URL");
        try (var admin = DriverManager.getConnection(givenUrl, user, password);
                var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /** {@code jdbc:postgresql://host:port/given?params} 의 DB 이름만 바꾼다. */
    private static String replaceDatabase(String jdbcUrl, String database) {
        int query = jdbcUrl.indexOf('?');
        String base = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
        String parameters = query < 0 ? "" : jdbcUrl.substring(query);
        int lastSlash = base.lastIndexOf('/');
        assertThat(lastSlash).as("DB 이름이 있는 JDBC URL").isGreaterThan(0);
        return base.substring(0, lastSlash + 1) + database + parameters;
    }

    @BeforeEach
    void loadFixture() throws Exception {
        connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL search_path = npick, public");
            statement.execute(resource("search/scene-candidate-fixture.sql"));
        }
        dataSource = new SingleConnectionDataSource(connection, true);
    }

    @AfterEach
    void rollbackFixture() throws Exception {
        if (dataSource != null) dataSource.destroy();
        if (connection != null && !connection.isClosed()) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** 완료 조건: 질의어가 캡션에 매칭된다. 제설 은 표본에서 캡션에만 있다. */
    @Test
    void matchesCaptionOnlyToken() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("제설")))).containsExactly(35L);
    }

    /** 완료 조건: 질의어가 화면 글자(OCR)에 매칭된다. 속보 는 표본에서 OCR 에만 있다. */
    @Test
    void matchesOcrOnlyToken() {
        var candidates = adapter(1, 1, 1, 10).findByWords(List.of("속보"));
        assertThat(sceneIds(candidates)).containsExactly(34L);
        var only = candidates.getFirst();
        assertThat(only.clipId()).isEqualTo(11L);
        assertThat(only.textScore()).isZero();
        assertThat(only.ocrScore()).isPositive();
        assertThat(only.score()).isEqualTo(only.ocrScore());
    }

    /** 원인 은 표본에서 대사에만 있다. */
    @Test
    void matchesTranscriptOnlyToken() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("원인")))).containsExactly(31L);
    }

    /** 재처리로 폐기된 처리의 장면은 같은 토큰을 갖고 있어도 나오지 않는다 (F-14). */
    @Test
    void excludesScenesOfNonActivePipelineRun() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("화재"))))
                .containsExactlyInAnyOrder(30L, 31L, 34L)
                .doesNotContain(32L);
    }

    /** 캡션·OCR 양쪽에 걸린 장면은 한 행으로 합쳐지고 채널 점수가 둘 다 남는다. */
    @Test
    void mergesBothChannelsIntoOneCandidate() {
        var scene30 = adapter(1, 1, 1, 10).findByWords(List.of("화재", "단독")).stream()
                .filter(candidate -> candidate.sceneId() == 30L)
                .toList();
        assertThat(scene30).hasSize(1);
        assertThat(scene30.getFirst().textScore()).isPositive();
        assertThat(scene30.getFirst().ocrScore()).isPositive();
    }

    /** 가중치 0 은 그 필드를 검색 대상에서 뺀다 — 설정으로 대상 필드를 정한다는 요구의 실동작. */
    @Test
    void zeroWeightRemovesFieldFromSearch() {
        assertThat(sceneIds(adapter(1, 1, 0, 10).findByWords(List.of("속보"))))
                .as("OCR 가중치 0 이면 화면 글자로만 걸리는 장면은 후보가 아니다")
                .isEmpty();
        assertThat(sceneIds(adapter(1, 0, 1, 10).findByWords(List.of("원인"))))
                .as("대사 가중치 0 이면 대사로만 걸리는 장면은 후보가 아니다")
                .isEmpty();
        assertThat(sceneIds(adapter(0, 1, 1, 10).findByWords(List.of("제설"))))
                .as("캡션 가중치 0 이면 캡션으로만 걸리는 장면은 후보가 아니다")
                .isEmpty();
    }

    /** 가중치가 순위를 실제로 바꾼다. 대사에만 원인 이 있는 31 번이 대사 가중치를 올리면 앞으로 온다. */
    @Test
    void weightsChangeRanking() {
        var tokens = List.of("화재", "원인");
        assertThat(sceneIds(adapter(10, 1, 1, 10).findByWords(tokens)).getFirst())
                .as("캡션 가중치가 크면 캡션에 화재 가 있는 30 번이 위")
                .isEqualTo(30L);
        assertThat(sceneIds(adapter(1, 10, 1, 10).findByWords(tokens)).getFirst())
                .as("대사 가중치가 크면 대사에 두 토큰이 다 있는 31 번이 위")
                .isEqualTo(31L);
    }

    /** 후보 pool 크기는 설정이 정한다. */
    @Test
    void limitsCandidatePoolToConfiguredSize() {
        assertThat(adapter(1, 1, 1, 10).findByWords(List.of("화재"))).hasSize(3);
        assertThat(adapter(1, 1, 1, 1).findByWords(List.of("화재"))).hasSize(1);
    }

    /** 내용어가 없으면 DB 를 부르지 않는다. 커넥션을 닫아 두면 질의가 일어났는지 알 수 있다. */
    @Test
    void doesNotQueryWhenNoUsableToken() throws Exception {
        var adapter = adapter(1, 1, 1, 10);
        connection.rollback();
        connection.close();
        assertThat(adapter.findByWords(List.of())).isEmpty();
        assertThat(adapter.findByWords(List.of("  ", ""))).isEmpty();
    }

    /** 토큰에 공백이 있으면 DB 에서 두 토큰으로 쪼개져 검색어가 조용히 달라진다. 우리 토크나이저가 만든 값이므로 5xx 로 거부한다. */
    @Test
    void rejectsTokenContainingWhitespace() {
        assertThatThrownBy(() -> adapter(1, 1, 1, 10).findByWords(List.of("공장 화재")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo("SRCH_500_001"));
    }

    /** 논리 삭제된 클립의 장면은 색인에 남아 있어도 후보가 아니다 (FRD §6.1 "active_pipeline_run_id 와 논리 삭제 여부"). */
    @Test
    void excludesScenesOfSoftDeletedClip() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("화재")))).doesNotContain(36L);
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("제설"))))
                .as("삭제된 클립만 갖고 있던 토큰이면 결과가 비어야 한다")
                .containsExactly(35L);
    }

    /** 화면 글자는 장면당 max 로 모은다. 합으로 모으면 키프레임이 많은 장면이 내용과 무관하게 이긴다. */
    @Test
    void aggregatesOcrScoreByMaxNotSum() {
        // 장면 34 는 키프레임 40·41 이 둘 다 '화재' 에 걸린다. 합이면 한 건짜리의 두 배가 된다.
        var twoHits = onlyCandidate(adapter(0, 0, 1, 10).findByWords(List.of("화재")), 34L);
        var oneHit = onlyCandidate(adapter(0, 0, 1, 10).findByWords(List.of("속보")), 34L);

        assertThat(twoHits.ocrScore()).as("같은 토큰이 키프레임 둘에 걸려도 최대값 하나만 쓴다").isEqualTo(twoHits.score());
        assertThat(twoHits.ocrScore()).isLessThan(oneHit.ocrScore() * 2);
    }

    /** 가중치 0 인 채널의 점수는 결과에 남지 않는다 — explain·재순위가 끈 채널을 되살리면 안 된다. */
    @Test
    void doesNotReportScoreOfDisabledChannel() {
        // 장면 30 은 캡션('화재')과 화면 글자('단독') 양쪽에 걸린다.
        var candidate = onlyCandidate(adapter(1, 1, 0, 10).findByWords(List.of("화재", "단독")), 30L);

        assertThat(candidate.textScore()).isPositive();
        assertThat(candidate.ocrScore()).isZero();
        assertThat(candidate.score()).isEqualTo(candidate.textScore());
    }

    /** null 은 빈 목록과 다르다. 호출부 배선 실수를 "결과 없음" 으로 위장하지 않는다. */
    @Test
    void rejectsNullTokenList() {
        assertThatThrownBy(() -> adapter(1, 1, 1, 10).findByWords(null)).isInstanceOf(NullPointerException.class);
    }

    private static SceneCandidateResult onlyCandidate(List<SceneCandidateResult> candidates, long sceneId) {
        var matched = candidates.stream()
                .filter(candidate -> candidate.sceneId() == sceneId)
                .toList();
        assertThat(matched).as("장면 %d 가 후보에 있어야 한다", sceneId).hasSize(1);
        return matched.getFirst();
    }

    private WordSceneCandidateAdapter adapter(double caption, double transcript, double ocr, int poolSize) {
        return new WordSceneCandidateAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new SceneCandidateProperties("test-candidate", caption, transcript, ocr, poolSize));
    }

    private static List<Long> sceneIds(List<SceneCandidateResult> candidates) {
        return candidates.stream().map(SceneCandidateResult::sceneId).toList();
    }

    private static String resource(String path) throws Exception {
        try (var stream = WordSceneCandidateAdapterTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
