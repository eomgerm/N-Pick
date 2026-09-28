package com.npick.support;

import java.sql.DriverManager;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 전체가 공유하는 일회용 PostgreSQL. 도커가 없으면 스킵이 아니라 실패한다. 검증하지 않은 것을 초록불로 위장하지 않기 위함이다.
 *
 * <p>이미지는 compose 와 같은 paradedb 다. pg_search(BM25)·pgvector 가 없으면 baseline 마이그레이션부터 실행되지 않는다.
 *
 * <p>JUnit 의 {@code @Container} 대신 정적 싱글턴으로 띄운다. 클래스마다 컨테이너를 새로 띄우면 같은 이미지를 테스트 클래스 수만큼 기동한다. 종료는 Testcontainers 의 Ryuk
 * 이 JVM 종료 후 처리하므로 {@code stop()} 을 부르지 않는다.
 *
 * <p>롤백 없이 커밋하는 테스트는 공유 DB 대신 {@link #freshDatabase} 로 전용 DB 를 받는다. 남은 행이 다른 클래스의 전역 단언을 깨뜨리지 않게 한다.
 */
public final class NpickPostgres {
    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(
                    DockerImageName.parse("paradedb/paradedb:0.25.6-pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("npick")
            .withUsername("npick")
            .withPassword("npick");

    static {
        CONTAINER.start();
        execute(CONTAINER.getJdbcUrl(), "CREATE SCHEMA IF NOT EXISTS npick");
        migrate(CONTAINER.getJdbcUrl());
    }

    private NpickPostgres() {}

    /** 공유 DB 접속 정보. baseline 은 이 클래스가 로딩되는 시점에 이미 적용돼 있다. */
    public static void datasource(DynamicPropertyRegistry properties) {
        datasource(properties, CONTAINER.getJdbcUrl());
    }

    /** {@link #freshDatabase} 로 받은 전용 DB 를 쓸 때의 접속 정보. */
    public static void datasource(DynamicPropertyRegistry properties, String url) {
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", CONTAINER::getUsername);
        properties.add("spring.datasource.password", CONTAINER::getPassword);
        // 조회 어댑터의 네이티브 SQL 이 테이블명을 스키마로 한정하지 않는다. 세션 search_path 를 직접 건다.
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO npick, public");
        // 단일 JVM 이 Spring 컨텍스트 16개를 캐시하고 각자 Hikari 풀을 유지한다. 기본 10 이면 160 커넥션으로 컨테이너
        // max_connections(100) 를 넘겨 전체 스위트가 "too many clients" 로 무너진다. 직렬 테스트라 3 이면 충분하다(16×3=48).
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }

    /**
     * 같은 컨테이너 안에 빈 DB 를 새로 만들고 접속 URL 을 준다. 스키마만 만들고 마이그레이션은 하지 않는다. baseline 이 빈 DB 에 적용되는 것 자체를 검증하는 호출자가 있기 때문이다.
     *
     * <p>이름은 호출자가 주는 리터럴이다. 식별자는 바인딩 파라미터로 넘길 수 없어 문자열로 잇는다. 외부 입력을 넘기지 않는다.
     */
    public static String freshDatabase(String name) {
        execute(CONTAINER.getJdbcUrl(), "CREATE DATABASE " + name);
        String url = "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getFirstMappedPort() + "/" + name;
        // 운영과 같은 역할 분담이다. 스키마는 프로비저닝(compose 의 postgres-init)이 만들고 Flyway 는 create-schemas=false 로 채운다.
        execute(url, "CREATE SCHEMA IF NOT EXISTS npick");
        return url;
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }

    /** SQL 한 문장을 실행한다. Spring 컨텍스트가 뜨기 전에 DB 를 준비하는 용도다. */
    public static void execute(String url, String sql) {
        try (var connection = DriverManager.getConnection(url, CONTAINER.getUsername(), CONTAINER.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }

    /** baseline 을 적용한다. 운영 설정과 같은 값을 쓴다. */
    public static void migrate(String url) {
        Flyway.configure()
                .dataSource(url, CONTAINER.getUsername(), CONTAINER.getPassword())
                .defaultSchema("npick")
                .schemas("npick")
                .createSchemas(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
