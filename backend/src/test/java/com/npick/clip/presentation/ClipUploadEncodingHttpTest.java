package com.npick.clip.presentation;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.UploadClipCommand;
import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 브라우저·curl 이 실제로 보내는 multipart 를 그대로 실어 제목이 UTF-8 로 읽히는지 본다 (S15P21A501-226).
 *
 * <p>MockMvc 로는 잡히지 않는 결함이다 — {@code MockMultipartHttpServletRequestBuilder} 는 charset 이 없는 텍스트 파트를 UTF-8 로 읽는 반면, 실제
 * 서블릿 컨테이너는 요청 인코딩이 정해지지 않은 multipart 본문을 ISO-8859-1 로 읽는다. 그래서 실제 Tomcat 에 실제 바이트를 보낸다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.session.cookie.secure=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ClipUploadEncodingHttpTest {
    private static final String KOREAN_TITLE = "설 연휴 교통 정보";
    private static final String BOUNDARY = "npick-226-boundary";

    static final String URL = Database.URL;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @MockitoBean
    UploadClipUseCase useCase;

    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties, URL);
    }

    private static class Database {
        static final String URL = create();

        static String create() {
            String url = NpickPostgres.freshDatabase("clip_upload_encoding_http");
            NpickPostgres.migrate(url);
            return url;
        }
    }

    @BeforeAll
    void login() throws Exception {
        jdbc.update(
                "INSERT INTO npick.member VALUES (911, 'clip-encoding-http', ?, '검수자', 'REVIEWER', now(), now())",
                encoder.encode("encoding-test"));
        send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET());
        var response = send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("X-XSRF-TOKEN", csrf())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"loginId\":\"clip-encoding-http\",\"password\":\"encoding-test\"}")));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void koreanTitleSurvivesAMultipartPartWithoutACharset() throws Exception {
        when(useCase.upload(any())).thenReturn(new RegisterClipResult(1L, 2L, "processing"));

        var response = send(HttpRequest.newBuilder(uri("/api/v1/clips"))
                .header("X-XSRF-TOKEN", csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body())));

        assertThat(response.statusCode()).isEqualTo(201);
        var command = org.mockito.ArgumentCaptor.forClass(UploadClipCommand.class);
        verify(useCase).upload(command.capture());
        assertThat(command.getValue().title()).isEqualTo(KOREAN_TITLE);
    }

    /** charset 파라미터가 없는 텍스트 파트. 브라우저의 {@code FormData} 와 curl 의 {@code --form-string} 이 보내는 형태 그대로다. */
    private static byte[] body() throws Exception {
        var out = new ByteArrayOutputStream();
        out.write(part("video", "tiny.mp4", "video/mp4"));
        out.write("fake video bytes".getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.write(field("source_type", "archive"));
        out.write(field("title", KOREAN_TITLE));
        out.write(field("rights_confirmed", "true"));
        out.write(field("external_processing_confirmed", "false"));
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] field(String name, String value) {
        var header = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n";
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] part(String name, String filename, String contentType) {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
                        + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private String csrf() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
