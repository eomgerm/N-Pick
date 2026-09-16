package com.npick.clip.presentation;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.clip.application.error.SceneThumbnailErrorCode;
import com.npick.clip.application.query.thumbnail.GetSceneThumbnailUseCase;
import com.npick.clip.application.query.thumbnail.SceneThumbnailResult;
import com.npick.clip.presentation.controller.SceneThumbnailController;
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
import com.npick.member.infrastructure.security.MemberUserDetailsService;
import com.npick.member.presentation.AuthController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
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
        controllers = {SceneThumbnailController.class, AuthController.class},
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
class SceneThumbnailControllerTest {

    private static final byte[] IMAGE = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String URL = "/api/v1/scenes/30/thumbnail";

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder passwords;

    @MockitoBean
    MemberUserDetailsService members;

    @MockitoBean
    GetSceneThumbnailUseCase thumbnail;

    @BeforeEach
    void setUp() {
        when(members.loadUserByUsername("reviewer"))
                .thenReturn(new AuthenticatedMember(7, "reviewer", passwords.encode("pw"), "REVIEWER"));
        when(members.loadUserByUsername("editor"))
                .thenReturn(new AuthenticatedMember(8, "editor", passwords.encode("pw"), "EDITOR"));
    }

    @Test
    void servesTheRepresentativeImageBytesWithItsOwnContentType() throws Exception {
        when(thumbnail.get(30)).thenReturn(new SceneThumbnailResult("image/jpeg", IMAGE));

        mvc.perform(get(URL).session(login("editor")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Length", String.valueOf(IMAGE.length)))
                .andExpect(header().string("Content-Disposition", "inline"))
                // 서버가 정한 형식을 브라우저가 다시 추측하지 않는다 (FRD §6.4).
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(IMAGE));
    }

    /** 카드 목록이 화면을 오갈 때마다 다시 내려받지 않게 브라우저 캐시를 허용하되 공유 캐시에는 남기지 않는다. */
    @Test
    void letsTheBrowserCacheTheImageButNotAnySharedCache() throws Exception {
        when(thumbnail.get(30)).thenReturn(new SceneThumbnailResult("image/jpeg", IMAGE));

        String cacheControl = mvc.perform(get(URL).session(login("editor")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("Cache-Control");

        assertThat(cacheControl).contains("private").contains("max-age=").doesNotContain("no-store");
    }

    @Test
    void keepsTheContentTypeTheAdapterDetermined() throws Exception {
        when(thumbnail.get(30)).thenReturn(new SceneThumbnailResult("image/png", IMAGE));

        mvc.perform(get(URL).session(login("editor"))).andExpect(header().string("Content-Type", "image/png"));
    }

    /** 세 실패를 서로 다른 코드로 구분한다. 화면이 「처리 중」 과 「파일 사고」 를 다르게 안내해야 한다 (FRD §6.2). */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "SCENE_NOT_FOUND, SCENE_404_001",
        "KEYFRAME_NOT_FOUND, SCENE_404_002",
        "THUMBNAIL_FILE_MISSING, SCENE_404_003"
    })
    void separatesMissingSceneMissingKeyframeAndMissingFile(SceneThumbnailErrorCode errorCode, String expectedCode)
            throws Exception {
        when(thumbnail.get(anyLong())).thenThrow(new BusinessException(errorCode));

        mvc.perform(get(URL).session(login("editor")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @CsvSource({"THUMBNAIL_LOCATION_REJECTED, 500", "THUMBNAIL_READ_FAILED, 503", "MEDIA_ROOT_UNAVAILABLE, 503"})
    void mapsStorageFailuresToTheirOwnStatus(SceneThumbnailErrorCode errorCode, int expectedStatus) throws Exception {
        when(thumbnail.get(anyLong())).thenThrow(new BusinessException(errorCode));

        mvc.perform(get(URL).session(login("editor"))).andExpect(status().is(expectedStatus));
    }

    /** 경로 이탈이 걸려도 절대 경로도, storage key 도, 예외 문자열도 응답에 남기지 않는다 (FRD §6.4). */
    @Test
    void hidesTheServerPathWhenTheStorageKeyEscapesMediaRoot() throws Exception {
        when(thumbnail.get(anyLong()))
                .thenThrow(new BusinessException(
                        SceneThumbnailErrorCode.THUMBNAIL_LOCATION_REJECTED,
                        new IllegalStateException("/srv/npick/media/runs/21/frames/s0001/kf-000000000.jpg")));

        String body = mvc.perform(get(URL).session(login("editor")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SCENE_500_001"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("/srv/", "storage_key", "kf-000000000", "IllegalStateException");
    }

    /** 경로가 아니라 ID 로만 접근한다. 경로 이탈은 핸들러에 도달조차 하지 않는다. */
    @ParameterizedTest
    @CsvSource({
        "/api/v1/scenes/..%2f..%2fetc%2fpasswd/thumbnail",
        "/api/v1/scenes/runs%2f21%2fframes/thumbnail",
        "/api/v1/scenes/-/thumbnail",
        "/api/v1/scenes/1.5/thumbnail"
    })
    void refusesAnythingButASceneId(String url) throws Exception {
        MockHttpSession session = login("editor");

        assertThat(mvc.perform(get(url).session(session))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isBetween(400, 404);
        verifyNoInteractions(thumbnail);
    }

    /** 편집기자와 검수자가 모두 결과 카드를 본다 (FRD F-07). 로그인하지 않으면 401 이다. */
    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @CsvSource({"anonymous, 401", "editor, 200", "reviewer, 200"})
    void allowsEveryLoggedInMemberToSeeTheThumbnail(String actor, int expectedStatus) throws Exception {
        when(thumbnail.get(anyLong())).thenReturn(new SceneThumbnailResult("image/jpeg", IMAGE));
        var request = get(URL);
        if (!actor.equals("anonymous")) {
            request.session(login(actor));
        }

        mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    /** 인증 판단이 UseCase 보다 먼저 내려져야 한다 — 그러지 않으면 장면의 존재 여부가 비로그인 응답으로 새어 나간다. */
    @Test
    void doesNotReachTheUseCaseWithoutALogin() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(thumbnail);
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
