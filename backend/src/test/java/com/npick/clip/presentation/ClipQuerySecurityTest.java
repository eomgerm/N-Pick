package com.npick.clip.presentation;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.npick.clip.application.query.ClipQueryResult;
import com.npick.clip.application.query.detail.GetClipUseCase;
import com.npick.clip.application.query.list.GetClipsResult;
import com.npick.clip.application.query.list.GetClipsUseCase;
import com.npick.clip.presentation.controller.ClipQueryController;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ClipQueryController.class, AuthController.class})
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
    ErrorTypeHttpStatusMapper.class
})
class ClipQuerySecurityTest {
    @MockitoBean
    RefreshLoginService refreshLogin;

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder passwords;

    @MockitoBean
    MemberUserDetailsService members;

    @MockitoBean
    GetClipsUseCase list;

    @MockitoBean
    GetClipUseCase detail;

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
    void anonymousCannotReadEitherEndpoint() throws Exception {
        for (String path : List.of("/api/v1/clips", "/api/v1/clips/10")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMM_401"));
        }
        verifyNoInteractions(list, detail);
    }

    @Test
    void editorAndUnrelatedRoleCannotReadEitherEndpoint() throws Exception {
        MockHttpSession session = login("editor");
        for (String path : List.of("/api/v1/clips", "/api/v1/clips/10", "/api/v1/clips?mine=true")) {
            mvc.perform(get(path).session(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMM_403"));
            mvc.perform(get(path).with(user("other").roles("OTHER"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(list, detail);
    }

    @Test
    void loggedInReviewerReadsBothWithoutCsrfOnGet() throws Exception {
        when(list.getClips(0, 20, List.of(), null))
                .thenReturn(new GetClipsResult(List.of(), 0, 20, 0, java.util.Map.of(), java.util.Map.of()));
        when(detail.getClip(10))
                .thenReturn(new ClipQueryResult(
                        10,
                        null,
                        "archive",
                        null,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        "none",
                        false,
                        false,
                        7,
                        new com.npick.member.application.query.MemberSummary(7, "reviewer"),
                        null,
                        null));
        MockHttpSession session = login("reviewer");
        mvc.perform(get("/api/v1/clips").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true));
        mvc.perform(get("/api/v1/clips/10").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clip.clip_id").value("10"));
    }

    @Test
    void mineFilterUsesTheReviewerIdFromTheSession() throws Exception {
        when(list.getClips(0, 20, List.of(), 7L))
                .thenReturn(new GetClipsResult(List.of(), 0, 20, 0, java.util.Map.of(), java.util.Map.of()));
        MockHttpSession session = login("reviewer");

        mvc.perform(get("/api/v1/clips?mine=true").session(session)).andExpect(status().isOk());

        verify(list).getClips(0, 20, List.of(), 7L);
    }

    @Test
    void clientSuppliedOwnerIdsAreIgnored() throws Exception {
        when(list.getClips(0, 20, List.of(), 7L))
                .thenReturn(new GetClipsResult(List.of(), 0, 20, 0, java.util.Map.of(), java.util.Map.of()));
        MockHttpSession session = login("reviewer");

        mvc.perform(get("/api/v1/clips?mine=true&registeredById=9&registered_by_id=9")
                        .session(session))
                .andExpect(status().isOk());

        verify(list).getClips(0, 20, List.of(), 7L);
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
