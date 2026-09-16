package com.npick.clip.presentation;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 PostgreSQL·media root 파일·세션 로그인·HTTP 를 연결해 썸네일 조회를 끝에서 끝까지 확인한다 (S15P21A501-170 완료 조건).
 *
 * <p>slice 테스트는 계층마다 반대편을 대역으로 세운다. 배선이 실제로 이어졌는지 — 어댑터 bean 이 등록되고, 보안 경로가 이 주소를 통과시키고, media root 설정을 같은 값으로 읽는지 — 는
 * 여기서만 드러난다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 테스트 서버는 loopback HTTP 다. 운영은 HTTPS 이므로 secure cookie 를 여기서만 끈다.
        properties = "server.servlet.session.cookie.secure=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SceneThumbnailHttpIntegrationTest {

    private static final Path ROOT =
            Path.of("build/scene-thumbnail-http-" + UUID.randomUUID()).toAbsolutePath();

    private static final long EDITOR_ID = 911L;
    private static final long REVIEWER_ID = 912L;
    private static final long CLIP_ID = 9110L;
    private static final long RUN_ID = 9120L;
    private static final long SCENE_WITH_KEYFRAMES = 9130L;
    private static final long SCENE_WITHOUT_KEYFRAMES = 9131L;
    private static final long SCENE_WITH_MISSING_FILE = 9132L;
    private static final long SCENE_WITH_ESCAPING_KEY = 9133L;

    private static final String EARLIEST_KEY = "runs/9120/frames/s0000/kf-000001000.jpg";
    private static final byte[] EARLIEST_IMAGE = jpeg("earliest");
    private static final byte[] LATER_IMAGE = jpeg("later");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client =
            HttpClient.newBuilder().cookieHandler(cookies).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties, Database.URL);
        properties.add("npick.clip-registration.media-root", ROOT::toString);
        properties.add("npick.clip-media.media-root", ROOT::toString);
    }

    private static class Database {
        static final String URL = create();

        static String create() {
            String url = NpickPostgres.freshDatabase("scene_thumbnail_http");
            NpickPostgres.migrate(url);
            return url;
        }
    }

    @BeforeAll
    void seed() throws Exception {
        Files.createDirectories(ROOT.resolve("runs/9120/frames/s0000"));
        Files.write(ROOT.resolve(EARLIEST_KEY), EARLIEST_IMAGE);
        Files.write(ROOT.resolve("runs/9120/frames/s0000/kf-000005000.jpg"), LATER_IMAGE);

        jdbc.update(
                "INSERT INTO npick.member VALUES (?, 'scene-thumb-editor', ?, '편집기자', 'EDITOR', now(), now())",
                EDITOR_ID,
                encoder.encode("thumb-test"));
        jdbc.update(
                "INSERT INTO npick.member VALUES (?, 'scene-thumb-reviewer', ?, '검수자', 'REVIEWER', now(), now())",
                REVIEWER_ID,
                encoder.encode("thumb-test"));
        jdbc.update("""
                INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, transcript_source,
                    registered_by_id, created_at, updated_at)
                VALUES (?, 'broadcast', 'clips/9110/original', repeat('a', 64), 'none', ?, now(), now())
                """, CLIP_ID, EDITOR_ID);
        jdbc.update("""
                INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,
                    stage_states_json, created_at, updated_at)
                VALUES (?, ?, 1, 'test-pipeline-v1', 'succeeded', '{}'::jsonb, now(), now())
                """, RUN_ID, CLIP_ID);
        jdbc.update("UPDATE npick.clip SET active_pipeline_run_id = ? WHERE clip_id = ?", RUN_ID, CLIP_ID);
        for (long sceneId : new long[] {
            SCENE_WITH_KEYFRAMES, SCENE_WITHOUT_KEYFRAMES, SCENE_WITH_MISSING_FILE, SCENE_WITH_ESCAPING_KEY
        }) {
            jdbc.update("""
                    INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms,
                        shot_type, created_at, updated_at)
                    VALUES (?, ?, ?, 0, 10000, 'b_roll', now(), now())
                    """, sceneId, CLIP_ID, RUN_ID);
        }
        // 가장 이른 프레임이 나중에 들어간 행이다. 삽입 순서로 고르면 틀린다.
        jdbc.update(
                "INSERT INTO npick.keyframe VALUES (9141, ?, 5000, 'runs/9120/frames/s0000/kf-000005000.jpg')",
                SCENE_WITH_KEYFRAMES);
        jdbc.update("INSERT INTO npick.keyframe VALUES (9142, ?, 1000, ?)", SCENE_WITH_KEYFRAMES, EARLIEST_KEY);
        jdbc.update(
                "INSERT INTO npick.keyframe VALUES (9143, ?, 0, 'runs/9120/frames/s0000/kf-999999999.jpg')",
                SCENE_WITH_MISSING_FILE);
        jdbc.update(
                "INSERT INTO npick.keyframe VALUES (9144, ?, 0, '../outside-the-root.jpg')", SCENE_WITH_ESCAPING_KEY);
    }

    /** 완료 조건: sceneId 로 최초 keyframe 이미지가 돌아온다. */
    @Test
    void returnsTheEarliestKeyframeImageForASceneId() throws Exception {
        login("scene-thumb-editor");

        HttpResponse<byte[]> response = image(SCENE_WITH_KEYFRAMES);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(EARLIEST_IMAGE);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                .contains("private")
                .contains("max-age=");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scene-thumb-editor", "scene-thumb-reviewer"})
    void letsBothEditorsAndReviewersSeeIt(String loginId) throws Exception {
        login(loginId);

        assertThat(image(SCENE_WITH_KEYFRAMES).statusCode()).isEqualTo(200);
    }

    @Test
    void refusesAnonymousAccess() throws Exception {
        cookies.getCookieStore().removeAll();

        assertThat(image(SCENE_WITH_KEYFRAMES).statusCode()).isEqualTo(401);
    }

    /** 세 가지 404 가 서로 다른 코드로 내려간다. 화면이 안내를 고를 수 있어야 한다 (FRD §6.2). */
    @Test
    void separatesUnknownSceneMissingKeyframeAndMissingFile() throws Exception {
        login("scene-thumb-editor");

        assertThatFails(999_999L, 404, "SCENE_404_001");
        assertThatFails(SCENE_WITHOUT_KEYFRAMES, 404, "SCENE_404_002");
        assertThatFails(SCENE_WITH_MISSING_FILE, 404, "SCENE_404_003");
    }

    /** media root 를 벗어나는 storage key 는 파일이 있든 없든 거부하고, 경로를 응답에 남기지 않는다 (FRD §6.4). */
    @Test
    void blocksAStorageKeyThatLeavesTheMediaRoot() throws Exception {
        login("scene-thumb-editor");
        Path outside = ROOT.getParent().resolve("outside-the-root.jpg");
        Files.write(outside, EARLIEST_IMAGE);
        try {
            String body = assertThatFails(SCENE_WITH_ESCAPING_KEY, 500, "SCENE_500_001");

            assertThat(body)
                    .doesNotContain(ROOT.toString())
                    .doesNotContain("outside-the-root")
                    .doesNotContain("storage_key");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private String assertThatFails(long sceneId, int expectedStatus, String expectedCode) throws Exception {
        HttpResponse<byte[]> response = image(sceneId);
        String body = new String(response.body(), StandardCharsets.UTF_8);

        assertThat(response.statusCode()).withFailMessage(body).isEqualTo(expectedStatus);
        assertThat(body).contains("\"code\":\"" + expectedCode + "\"").contains("\"isSuccess\":false");
        return body;
    }

    private HttpResponse<byte[]> image(long sceneId) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri("/api/v1/scenes/" + sceneId + "/thumbnail"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private void login(String loginId) throws Exception {
        cookies.getCookieStore().removeAll();
        client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String csrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .findFirst()
                .orElseThrow()
                .getValue();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                        .header("X-XSRF-TOKEN", csrf)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginId\":\"" + loginId + "\",\"password\":\"thumb-test\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static byte[] jpeg(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] image = new byte[3 + body.length];
        image[0] = (byte) 0xFF;
        image[1] = (byte) 0xD8;
        image[2] = (byte) 0xFF;
        System.arraycopy(body, 0, image, 3, body.length);
        return image;
    }
}
