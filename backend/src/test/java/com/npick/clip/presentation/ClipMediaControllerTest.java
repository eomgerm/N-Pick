package com.npick.clip.presentation;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.query.media.ClipMediaStreamResult;
import com.npick.clip.application.query.media.StreamClipMediaQuery;
import com.npick.clip.application.query.media.StreamClipMediaUseCase;
import com.npick.clip.presentation.controller.ClipMediaController;
import com.npick.common.config.WebConfig;
import com.npick.common.error.BusinessException;
import com.npick.common.error.handler.ApiErrorResponseWriter;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
import com.npick.common.error.handler.GlobalExceptionHandler;
import com.npick.common.security.AuthenticatedMember;
import com.npick.common.security.config.SecurityConfig;
import com.npick.common.security.config.SecurityWebMvcConfig;
import com.npick.common.security.handler.RestAccessDeniedHandler;
import com.npick.common.security.handler.RestAuthenticationEntryPoint;
import com.npick.common.security.resolver.CurrentMemberArgumentResolver;
import com.npick.member.application.command.RefreshLoginService;
import com.npick.member.application.command.login.RegisterRefreshUseCase;
import com.npick.member.infrastructure.security.MemberUserDetailsService;
import com.npick.member.presentation.AuthController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {ClipMediaController.class, AuthController.class},
        properties = {"cors.allowed-origins[0]=https://npick.test"})
@Import({
    com.npick.member.application.command.MemberLoginService.class,
    com.npick.member.infrastructure.security.MemberAuthenticationAdapter.class,
    SecurityConfig.class,
    WebConfig.class,
    SecurityWebMvcConfig.class,
    CurrentMemberArgumentResolver.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    ApiErrorResponseWriter.class,
    ErrorTypeHttpStatusMapper.class,
    GlobalExceptionHandler.class
})
class ClipMediaControllerTest {
    @MockitoBean
    RefreshLoginService refreshLogin;

    private static final byte[] CONTENT = "0123456789".repeat(10).getBytes(StandardCharsets.UTF_8);
    private static final String URL = "/api/v1/media/42";
    private static final String ALLOWED_ORIGIN = "https://npick.test";

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder passwords;

    @MockitoBean
    MemberUserDetailsService members;

    @MockitoBean
    StreamClipMediaUseCase stream;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(refreshLogin.register(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RegisterRefreshUseCase.IssuedRefresh(
                        "a".repeat(64), java.time.Instant.now().plusSeconds(28800)));
        when(members.loadUserByUsername("reviewer"))
                .thenReturn(new AuthenticatedMember(7, "reviewer", passwords.encode("pw"), "REVIEWER"));
        when(members.loadUserByUsername("editor"))
                .thenReturn(new AuthenticatedMember(8, "editor", passwords.encode("pw"), "EDITOR"));
    }

    @Test
    void streamsWholeVideoAndAdvertisesRangeSupport() throws Exception {
        when(stream.stream(any())).thenReturn(whole());

        mvc.perform(get(URL).session(login("editor")))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", String.valueOf(CONTENT.length)))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().doesNotExist("Content-Range"))
                .andExpect(header().doesNotExist("X-Accel-Redirect"))
                .andExpect(content().bytes(CONTENT));
    }

    @Test
    void answersRangeRequestWith206AndTheRequestedWindow() throws Exception {
        when(stream.stream(new StreamClipMediaQuery(42, "bytes=10-19"))).thenReturn(window(10, 10));

        mvc.perform(get(URL).session(login("editor")).header("Range", "bytes=10-19"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 10-19/100"))
                .andExpect(header().string("Content-Length", "10"))
                .andExpect(content().bytes("0123456789".getBytes(StandardCharsets.UTF_8)));
    }

    /** 마지막 바이트까지의 구간도 Content-Range 분모가 전체 길이여야 브라우저가 seek 를 이어간다. */
    @Test
    void keepsTotalLengthInContentRangeForTheLastWindow() throws Exception {
        when(stream.stream(any())).thenReturn(window(95, 5));

        mvc.perform(get(URL).session(login("editor")).header("Range", "bytes=95-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 95-99/100"));
    }

    @Test
    void returnsDistinctCodeWhenTheRangeCannotBeSatisfied() throws Exception {
        when(stream.stream(any())).thenThrow(new BusinessException(ClipMediaErrorCode.RANGE_NOT_SATISFIABLE));

        mvc.perform(get(URL).session(login("editor")).header("Range", "bytes=500-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("CLIP_416_001"))
                .andExpect(jsonPath("$.message").value("요청한 재생 구간이 영상 길이를 벗어났습니다."));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"CLIP_NOT_FOUND, CLIP_404_001", "MEDIA_FILE_MISSING, CLIP_404_002"})
    void separatesMissingClipFromMissingFile(ClipMediaErrorCode errorCode, String expectedCode) throws Exception {
        when(stream.stream(any())).thenThrow(new BusinessException(errorCode));

        mvc.perform(get(URL).session(login("editor")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    /** 절대 경로도, 파일이 있었는지도 응답에 남기지 않는다 (FR-RES-013). */
    @Test
    void hidesTheServerPathWhenTheStorageKeyEscapesMediaRoot() throws Exception {
        when(stream.stream(any())).thenThrow(new BusinessException(ClipMediaErrorCode.MEDIA_LOCATION_REJECTED));

        String body = mvc.perform(get(URL).session(login("editor")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CLIP_500_003"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("/srv/", "media-root", "original");
    }

    /** 전송 중 실패는 오류 Envelope 로 바뀐다. 앞서 붙인 영상용 길이·구간 헤더가 남으면 컨테이너가 JSON 본문을 그 길이에서 잘라낸다. */
    @ParameterizedTest(name = "partial={0}")
    @ValueSource(booleans = {true, false})
    void dropsVideoHeadersWhenTheBodyFailsBeforeTheResponseIsCommitted(boolean partial) throws Exception {
        int offset = partial ? 10 : 0;
        int length = partial ? 10 : CONTENT.length;
        when(stream.stream(any()))
                .thenReturn(new ClipMediaStreamResult(
                        "video/mp4", CONTENT.length, offset, length, partial, null, target -> {
                            throw new BusinessException(ClipMediaErrorCode.MEDIA_READ_FAILED);
                        }));
        var request = get(URL).session(login("editor")).header("Origin", ALLOWED_ORIGIN);
        if (partial) {
            request.header("Range", "bytes=10-19");
        }

        MockHttpServletResponse response = mvc.perform(request)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CLIP_503_010"))
                .andExpect(header().doesNotExist("Content-Range"))
                // 오류 본문을 브라우저 스크립트가 읽어야 사용자에게 실패를 안내할 수 있다 (FRD F-07).
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("Content-Length"))
                .satisfiesAnyOf(
                        declared -> assertThat(declared).isNull(),
                        declared -> assertThat(declared)
                                .isEqualTo(String.valueOf(response.getContentAsByteArray().length)));
    }

    @Test
    void delegatesBytesToTheProxyWithoutWritingThemItself() throws Exception {
        when(stream.stream(any()))
                .thenReturn(new ClipMediaStreamResult(
                        "video/mp4",
                        CONTENT.length,
                        0,
                        CONTENT.length,
                        false,
                        "/internal-media/clips/42/original", //
                        target -> {
                            throw new AssertionError("프록시 위임에서는 본문을 쓰지 않는다");
                        }));

        mvc.perform(get(URL).session(login("editor")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect", "/internal-media/clips/42/original"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                // 길이·구간 헤더는 프록시가 만든다. 우리가 붙이면 어긋난다.
                .andExpect(header().doesNotExist("Content-Range"))
                .andExpect(content().bytes(new byte[0]));
    }

    /** 경로가 아니라 ID 로만 접근한다. 경로 이탈은 핸들러에 도달조차 하지 않는다. */
    @ParameterizedTest
    @CsvSource({"/api/v1/media/..%2f..%2fetc%2fpasswd", "/api/v1/media/clips", "/api/v1/media/-", "/api/v1/media/1.5"})
    void refusesAnythingButAClipId(String url) throws Exception {
        MockHttpSession session = login("editor");

        assertThat(mvc.perform(get(url).session(session))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isBetween(400, 404);
        verifyNoInteractions(stream);
    }

    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @CsvSource({"anonymous, 401", "editor, 200", "reviewer, 200"})
    void allowsEveryLoggedInMemberToPreview(String actor, int expectedStatus) throws Exception {
        when(stream.stream(any())).thenReturn(whole());
        var request = get(URL);
        if (!actor.equals("anonymous")) {
            request.session(login(actor));
        }

        mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    private static ClipMediaStreamResult whole() {
        return new ClipMediaStreamResult("video/mp4", CONTENT.length, 0, CONTENT.length, false, null, target -> {
            try {
                target.write(CONTENT);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private static ClipMediaStreamResult window(int offset, int length) {
        return new ClipMediaStreamResult("video/mp4", CONTENT.length, offset, length, true, null, target -> {
            try {
                target.write(CONTENT, offset, length);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + username + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    }
}
