package com.npick.clip.presentation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.npick.clip.application.command.ClipUploadService;
import com.npick.clip.application.command.StoredClipRegistrationService;
import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.command.register.RegisterClipCommand;
import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.RegisterClipUseCase;
import com.npick.clip.application.command.register.UploadClipUseCase;
import com.npick.clip.application.command.store.StoreVideoResult;
import com.npick.clip.application.port.ClipRegistrationContextPort;
import com.npick.clip.infrastructure.config.ClipRegistrationConfiguration;
import com.npick.clip.infrastructure.security.SessionRegistrationActorAdapter;
import com.npick.clip.presentation.controller.ClipRegistrationController;
import com.npick.common.config.WebConfig;
import com.npick.common.error.handler.ApiErrorResponseWriter;
import com.npick.common.error.handler.ErrorTypeHttpStatusMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {ClipRegistrationController.class, AuthController.class},
        properties = {
            "cors.allowed-origins[0]=https://npick.test",
            "npick.clip-registration.media-root=${java.io.tmpdir}/security-test-media",
            "npick.clip-registration.upload-root=${java.io.tmpdir}/security-test-upload",
            "npick.clip-registration.probe-timeout=10s",
            "npick.clip-registration.decode-timeout=30s",
            "npick.clip-registration.pipeline-version=test-v1",
            "npick.clip-registration.stage-names[0]=scene_detection",
            "npick.clip-registration.external-processing-required=false",
            "npick.clip-registration.input.max-file-bytes=1024",
            "npick.clip-registration.input.max-duration-seconds=10",
            "npick.clip-registration.input.allowed-containers[0]=mp4",
            "npick.clip-registration.input.allowed-video-codecs[0]=h264",
            "npick.clip-registration.input.allowed-audio-codecs[0]=aac"
        })
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
    SessionRegistrationActorAdapter.class,
    com.npick.pipeline.infrastructure.config.PipelineDefinitionConfiguration.class,
    ClipRegistrationConfiguration.class
})
class ClipRegistrationSecurityTest {
    @MockitoBean
    RefreshLoginService refreshLogin;

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    ClipRegistrationContextPort context;

    @MockitoBean
    MemberUserDetailsService members;

    @MockitoBean
    UploadClipUseCase upload;

    @MockitoBean
    RegisterClipUseCase database;

    @BeforeEach
    void setup() {
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
    void sessionReviewerIdReachesRegistrationDespiteForgedRequestId() throws Exception {
        var video = mock(PrepareVideoResult.class);
        when(video.contentHash()).thenReturn("a".repeat(64));
        var stored = mock(StoreVideoResult.class);
        when(stored.storageKey()).thenReturn("clips/test/original");
        var flow = new ClipUploadService(
                context,
                command -> video,
                new StoredClipRegistrationService((id, prepared) -> stored, database),
                (key, actor, hash, request, create) -> create.get(),
                (subtitle, duration, id) -> {
                    throw new AssertionError("No subtitle in this request");
                });
        when(upload.upload(any())).thenAnswer(call -> flow.upload(call.getArgument(0)));
        when(database.register(any())).thenAnswer(call -> {
            RegisterClipCommand command = call.getArgument(0);
            assertThat(command.registeredById()).isEqualTo(7);
            return new RegisterClipResult(command.clipId(), command.pipelineRunId(), "queued");
        });
        mvc.perform(request().session(login("reviewer")).with(csrf()).param("registered_by_id", "999"))
                .andExpect(status().isCreated());
        verify(database).register(any());
        verify(video).close();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}: HTTP {2}")
    @org.junit.jupiter.params.provider.CsvSource({"anonymous, true, 401", "editor, true, 403", "reviewer, false, 403"})
    void deniesUnauthorizedUpload(String actor, boolean includeCsrf, int expectedStatus) throws Exception {
        var request = request();
        if (!actor.equals("anonymous")) request.session(login(actor));
        if (includeCsrf) request.with(csrf());
        mvc.perform(request).andExpect(status().is(expectedStatus));
        verifyNoInteractions(upload, database);
    }

    @Test
    void permitsCredentialedUploadPreflightWithRequiredHeaders() throws Exception {
        mvc.perform(options("/api/v1/clips")
                        .header("Origin", "https://npick.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,Idempotency-Key,X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://npick.test"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
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

    private MockMultipartHttpServletRequestBuilder request() {
        return multipart("/api/v1/clips")
                .file(new MockMultipartFile("video", "sample.mp4", "video/mp4", new byte[] {1}))
                .header("Idempotency-Key", "security-test")
                .param("source_type", "archive")
                .param("rights_confirmed", "true");
    }
}
