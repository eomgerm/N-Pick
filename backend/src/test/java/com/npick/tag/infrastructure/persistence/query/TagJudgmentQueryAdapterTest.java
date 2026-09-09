package com.npick.tag.infrastructure.persistence.query;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.npick.tag.application.query.SceneTagResolutionService;
import com.npick.tag.application.query.TagMatchRange;
import com.npick.tag.application.query.TagMatchedScene;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 실제 스키마에 질의해 상속 전개와 검색 대상 제외를 검증한다 (S15P21A501-161 완료 조건).
 *
 * <p>판정 순서 자체는 {@code TagResolutionPolicyTest} 가 DB 없이 검증한다. 여기서 확인하는 것은 <b>SQL 만 답할 수 있는 것</b> 넷이다 — 클립 태그의 장면 상속, 폐기된
 * 처리의 장면 제외, 논리 삭제된 클립 제외, 날짜 문자열 범위 비교.
 *
 * <p>전용 테스트 DB 환경 변수가 없으면 생략된다. 생략된 것은 통과가 아니다. 컨테이너 실행 방법은 backend README 의 통합 테스트 절을 따른다.
 *
 * <p>같은 서버에 <b>별도 데이터베이스</b> 를 만들어 쓴다. {@code FlywayBaselineTest} 는 "기존 npick 객체가 하나도 없을 때만" 실행되도록 자신을 보호하는데, 이 테스트가 같은
 * 스키마에 마이그레이션을 걸면 실행 순서에 따라 그 검사가 깨진다.
 *
 * <p>표본은 커밋하지 않고 롤백한다. 어댑터가 커밋 전 데이터를 보게 하려고 같은 커넥션을 {@link SingleConnectionDataSource} 로 감싼다 — 후보 검증 검색(F-12)이 의존하는
 * 성질과 같은 구성이다 (FRD §11).
 */
@EnabledIfEnvironmentVariable(named = "NPICK_MIGRATION_TEST_URL", matches = ".+")
class TagJudgmentQueryAdapterTest {
    private static final String DATABASE = "npick_tag_resolution_test";

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
        // Flyway 는 create-schemas=false 로 돌므로 npick 스키마를 먼저 만들어야 한다.
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
            statement.execute(resource("tag/tag-resolution-fixture.sql"));
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

    @Test
    @DisplayName("클립 태그는 활성 처리의 장면에만 상속되고, 장면별 반려는 그 장면만 끊는다")
    void inheritsClipTagToActiveScenesOnly() {
        var matched = service().find(List.of(TagMatchRange.exact(TagType.EVENT, "포항지진")));

        // 30 상속 · 31 장면 반려 · 32 폐기된 처리 · 34 논리 삭제된 클립
        assertThat(matched).extracting(TagMatchedScene::sceneId).containsExactly(30L);
        assertThat(matched.getFirst().clipId()).isEqualTo(10L);

        var tag = matched.getFirst().matchedTags();
        assertThat(tag).singleElement().satisfies(effective -> {
            assertThat(effective.scope()).isEqualTo(EffectiveTag.Scope.CLIP);
            assertThat(effective.verification()).isEqualTo(EffectiveTag.Verification.UNVERIFIED);
            assertThat(effective.name()).as("표시값은 정규화값과 따로 저장된다").isEqualTo("포항 지진");
        });
    }

    @Test
    @DisplayName("날짜 태그는 문자열 범위 비교로 찾는다 - 끝날짜는 포함이다")
    void findsDateTagByStringRange() {
        var march = service()
                .find(List.of(TagMatchRange.dates(
                        TagType.BROADCAST_DATE, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01"))));

        assertThat(march).extracting(TagMatchedScene::sceneId).containsExactly(33L);
        assertThat(march.getFirst().matchedTags())
                .singleElement()
                .satisfies(tag -> assertThat(tag.matchValue()).isEqualTo("2026-03-15"));

        var singleDay = service()
                .find(List.of(TagMatchRange.dates(
                        TagType.BROADCAST_DATE, LocalDate.parse("2026-03-15"), LocalDate.parse("2026-03-16"))));

        assertThat(singleDay)
                .as("반열린 구간의 마지막 날이 결과에 들어와야 한다")
                .extracting(TagMatchedScene::sceneId)
                .containsExactly(33L);
    }

    @Test
    @DisplayName("범위 밖의 날짜 태그는 걸리지 않는다")
    void excludesDateTagOutsideRange() {
        var matched = service()
                .find(List.of(TagMatchRange.dates(
                        TagType.BROADCAST_DATE, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-15"))));

        assertThat(matched).as("2026-03-15 는 반열린 구간의 끝이라 제외된다").isEmpty();
    }

    @Test
    @DisplayName("장면 방향 판정이 태그 방향 판정과 같은 결과를 낸다")
    void bothDirectionsAgree() {
        var resolved = service().resolve(List.of(30L, 31L, 32L, 34L));

        assertThat(resolved).as("반려된 장면과 검색 대상이 아닌 장면은 키가 없다").containsOnlyKeys(30L);
        assertThat(resolved.get(30L)).extracting(EffectiveTag::matchValue).containsExactlyInAnyOrder("포항지진", "홍길동");
    }

    @Test
    @DisplayName("추정 근거는 verified 로 저장돼 있어도 미검증으로 나온다")
    void demotesInferenceEvidenceStoredAsVerified() {
        var person = service().resolve(List.of(30L)).get(30L).stream()
                .filter(tag -> tag.tagType() == TagType.PERSON)
                .toList();

        assertThat(person).singleElement().satisfies(tag -> {
            assertThat(tag.source()).isEqualTo("asr");
            assertThat(tag.verification()).isEqualTo(EffectiveTag.Verification.UNVERIFIED);
            assertThat(tag.scope()).isEqualTo(EffectiveTag.Scope.SCENE);
        });
    }

    @Test
    @DisplayName("검증된 관측 근거는 검증됨으로 나온다 - 장면 자체 태그와 클립 상속 태그를 범위로 구분한다")
    void keepsVerifiedObservationEvidence() {
        var resolved = service().resolve(List.of(33L));

        // 장면 33 은 같은 종류(broadcast_date)의 태그를 둘 갖는다. 자기 태그와 클립에서 상속된 태그다.
        // 한 값으로 정해야 하는 속성의 충돌을 푸는 것은 판정기가 아니라 제외 판정(-58)의 몫이라
        // 여기서는 둘을 그대로, 범위를 구분해 돌려준다 (F-06·F-10).
        assertThat(resolved.get(33L))
                .extracting(EffectiveTag::matchValue, EffectiveTag::scope, EffectiveTag::source)
                .containsExactlyInAnyOrder(
                        tuple("2026-03-15", EffectiveTag.Scope.SCENE, "original_metadata"),
                        tuple("2026-04-20", EffectiveTag.Scope.CLIP, "ocr"));

        assertThat(resolved.get(33L))
                .allSatisfy(tag -> assertThat(tag.verification())
                        .isEqualTo(EffectiveTag.Verification.VERIFIED)
                        .matches(verification -> verification.trustedForConflict(), "F-06 충돌 판정에 쓸 수 있다"));
    }

    @Test
    @DisplayName("장면 번호가 6만 개를 넘어도 질의가 죽지 않는다 - 바인딩 파라미터 상한 65535")
    void survivesMoreSceneIdsThanBindParameterLimit() {
        // IN (:sceneIds) 는 id 하나당 파라미터 하나로 펼쳐져 65535 에서 터진다.
        // 태그 채널은 후보 개수를 제한하지 않으므로 그 후보가 그대로 넘어올 수 있다.
        var manyIds = new java.util.ArrayList<Long>(
                LongStream.rangeClosed(1_000, 71_000).boxed().toList());
        manyIds.add(30L);

        var resolved = service().resolve(manyIds);

        assertThat(manyIds).hasSizeGreaterThan(65_535);
        assertThat(resolved).as("표본에 있는 장면만 걸린다").containsOnlyKeys(30L);
    }

    @Test
    @DisplayName("조건이 비면 DB 를 부르지 않는다")
    void doesNotQueryWithoutCriteria() throws Exception {
        var service = service();
        connection.rollback();
        connection.close();

        assertThat(service.find(List.of())).isEmpty();
        assertThat(service.resolve(List.of())).isEmpty();
    }

    private SceneTagResolutionService service() {
        return new SceneTagResolutionService(new TagJudgmentQueryAdapter(new NamedParameterJdbcTemplate(dataSource)));
    }

    private static String resource(String path) throws Exception {
        try (var stream = TagJudgmentQueryAdapterTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
