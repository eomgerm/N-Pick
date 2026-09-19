package com.npick.member;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.npick.NpickApplication;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 쿠키와 PostgreSQL을 사용해 백엔드 종료·재기동 경계를 넘는 로그인을 검증한다. */
class PersistentLoginSessionHttpTest {
    @TempDir
    Path media;

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client =
            HttpClient.newBuilder().cookieHandler(cookies).build();
    private int port;

    @Test
    void loginSurvivesRestartAndActivityRenewsIdleTimeoutButLogoutAndExpiryStillRevokeIt() throws Exception {
        String url = NpickPostgres.freshDatabase("persistent_login");
        // 이미 배포된 스키마에서 업그레이드한다. 신규 세션 테이블은 실제 앱의 Flyway가 만든다.
        Flyway.configure()
                .dataSource(url, NpickPostgres.username(), NpickPostgres.password())
                .defaultSchema("npick")
                .schemas("npick")
                .createSchemas(false)
                .target("20260917170000")
                .load()
                .migrate();

        String savedCookie;
        try (var app = start(url)) {
            var jdbc = app.getBean(JdbcTemplate.class);
            jdbc.update(
                    "INSERT INTO npick.member VALUES (901, 'session-test', ?, '편집기자', 'EDITOR', now(), now())",
                    app.getBean(PasswordEncoder.class).encode("test-password"));
            assertThat(request("GET", "/auth/me", null).statusCode()).isEqualTo(401);
            var login = login();
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.headers().allValues("set-cookie"))
                    .anySatisfy(header -> assertThat(header)
                            .startsWith("JSESSIONID=")
                            .contains("Path=/", "HttpOnly", "SameSite=Strict"));
            savedCookie = cookie("JSESSIONID").getValue();
            // Next.js 서버 guard가 허용하는 쿠키 형식과 동일하다.
            assertThat(savedCookie).matches("(?i)^[a-z\\d._~-]+$");
            assertThat(cookies.getCookieStore().getCookies())
                    .noneMatch(c -> c.getName().equals("SESSION"));
            assertThat(jdbc.queryForObject(
                            "SELECT MAX_INACTIVE_INTERVAL FROM npick.SPRING_SESSION WHERE PRINCIPAL_NAME = 'session-test'",
                            Integer.class))
                    .isEqualTo(8 * 60 * 60);

            // 예전 30분 기본값을 넘겨도 유효하고, 서버 요청을 보내면 만료 시각이 다시 연장된다.
            long idleSince = System.currentTimeMillis() - Duration.ofMinutes(31).toMillis();
            jdbc.update(
                    "UPDATE npick.SPRING_SESSION SET LAST_ACCESS_TIME = ?, EXPIRY_TIME = ?",
                    idleSince,
                    idleSince + Duration.ofHours(8).toMillis());
            assertMember(request("GET", "/auth/me", null));
            assertThat(jdbc.queryForObject("SELECT LAST_ACCESS_TIME FROM npick.SPRING_SESSION", Long.class))
                    .isGreaterThan(idleSince);
        }

        // 서버·커넥션 풀·메모리 세션을 모두 닫고 같은 DB로 재기동한다. 브라우저 쿠키만 재사용한다.
        try (var app = start(url)) {
            var jdbc = app.getBean(JdbcTemplate.class);
            assertThat(cookie("JSESSIONID").getValue()).isEqualTo(savedCookie);
            assertMember(request("GET", "/auth/me", null));
            assertThat(request("GET", "/clips", null).statusCode()).isEqualTo(403);
            assertThat(request("POST", "/auth/logout", "").statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.SPRING_SESSION", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM npick.SPRING_SESSION_ATTRIBUTES", Integer.class))
                    .isZero();
            // 브라우저에서 지운 쿠키를 재전송해도 로그아웃한 세션이 부활하지 않는다.
            try (var replay = HttpClient.newHttpClient()) {
                var response = replay.send(
                        HttpRequest.newBuilder(uri("/auth/me"))
                                .header("Cookie", "JSESSIONID=" + savedCookie)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
            }

            assertThat(login().statusCode()).isEqualTo(200);
            long expiredSince = System.currentTimeMillis() - Duration.ofHours(9).toMillis();
            jdbc.update(
                    "UPDATE npick.SPRING_SESSION SET LAST_ACCESS_TIME = ?, EXPIRY_TIME = ?",
                    expiredSince,
                    expiredSince + Duration.ofHours(8).toMillis());
            var expired = request("GET", "/auth/me", null);
            assertThat(expired.statusCode()).isEqualTo(401);
            assertThat(expired.body()).contains("COMM_401");
        } finally {
            client.close();
        }
    }

    private ConfigurableApplicationContext start(String url) {
        var app = new SpringApplicationBuilder(NpickApplication.class)
                .run(
                        "--spring.profiles.active=test",
                        "--server.port=0",
                        "--server.servlet.session.cookie.secure=false",
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + NpickPostgres.username(),
                        "--spring.datasource.password=" + NpickPostgres.password(),
                        "--npick.clip-registration.media-root=" + media);
        port = Integer.parseInt(app.getEnvironment().getRequiredProperty("local.server.port"));
        return app;
    }

    private HttpResponse<String> login() throws Exception {
        assertThat(request("GET", "/auth/csrf", null).statusCode()).isEqualTo(200);
        return request("POST", "/auth/login", "{\"loginId\":\"session-test\",\"password\":\"test-password\"}");
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN").getValue())
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpCookie cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + "/api/v1" + path).normalize();
    }

    private void assertMember(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"memberId\":901", "\"role\":\"EDITOR\"");
    }
}
