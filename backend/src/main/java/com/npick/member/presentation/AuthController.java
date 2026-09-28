package com.npick.member.presentation;

import java.time.Duration;
import java.time.Instant;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.AuthenticatedMember;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.config.AccessSessionExpiryFilter;
import com.npick.common.security.resolver.LoginMember;
import com.npick.member.application.command.login.LoginCommand;
import com.npick.member.application.command.login.LoginUseCase;
import com.npick.member.application.command.login.RefreshLoginUseCase;
import com.npick.member.application.command.login.RegisterRefreshUseCase;
import com.npick.member.application.command.login.RevokeRefreshUseCase;
import com.npick.member.presentation.request.LoginRequest;
import com.npick.member.presentation.response.MemberResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final SecurityContextRepository securityContextRepository;
    private final RefreshLoginUseCase refreshLogin;
    private final RegisterRefreshUseCase registerRefresh;
    private final RevokeRefreshUseCase revokeRefresh;
    private final CookieSerializer sessionCookies;
    private final Duration accessLifetime;
    private final boolean secure;

    public AuthController(
            LoginUseCase loginUseCase,
            SecurityContextRepository securityContextRepository,
            RefreshLoginUseCase refreshLogin,
            RegisterRefreshUseCase registerRefresh,
            RevokeRefreshUseCase revokeRefresh,
            CookieSerializer sessionCookies,
            @Value("${server.servlet.session.timeout:30m}") Duration accessLifetime,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        this.loginUseCase = loginUseCase;
        this.securityContextRepository = securityContextRepository;
        this.refreshLogin = refreshLogin;
        this.registerRefresh = registerRefresh;
        this.revokeRefresh = revokeRefresh;
        this.sessionCookies = sessionCookies;
        this.accessLifetime = accessLifetime;
        this.secure = secure;
    }

    @PostMapping("/login")
    public ApiResponse<MemberResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        var result = loginUseCase.login(new LoginCommand(request.loginId(), request.password()));
        if (httpRequest.getSession(false) != null) httpRequest.getSession(false).invalidate();
        revokeRefresh.revoke(refreshToken(httpRequest));
        var principal = new AuthenticatedMember(result.memberId(), result.loginId(), null, result.role());
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());

        httpRequest.getSession(true);
        httpRequest.changeSessionId();
        httpRequest
                .getSession()
                .setAttribute(
                        AccessSessionExpiryFilter.EXPIRES_AT,
                        Instant.now().plus(accessLifetime).toEpochMilli());

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, httpRequest, httpResponse);
        var refresh = registerRefresh.register(httpRequest.getSession().getId(), result);
        writeRefreshCookie(httpResponse, refresh.token(), Duration.between(Instant.now(), refresh.expiresAt()));

        CurrentMember current = new CurrentMember(principal.memberId(), authentication.getName(), principal.role());
        return ApiResponse.success(MemberResponse.from(current));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        revokeRefresh.revoke(refreshToken(request));
        writeRefreshCookie(response, "", Duration.ZERO);
        return ApiResponse.success();
    }

    @PostMapping("/refresh")
    public ApiResponse<MemberResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        var result = refreshLogin.refresh(refreshToken(request));
        sessionCookies.writeCookieValue(new CookieSerializer.CookieValue(request, response, result.accessSessionId()));
        var member = result.member();
        return ApiResponse.success(
                MemberResponse.from(new CurrentMember(member.memberId(), member.loginId(), member.role())));
    }

    private String refreshToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals("NPICK_REFRESH")) return cookie.getValue();
            }
        }
        return null;
    }

    private void writeRefreshCookie(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(
                "Set-Cookie",
                ResponseCookie.from("NPICK_REFRESH", token)
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite("Strict")
                        .path("/")
                        .maxAge(maxAge)
                        .build()
                        .toString());
    }

    @GetMapping("/me")
    public ApiResponse<MemberResponse> me(@LoginMember CurrentMember member) {
        return ApiResponse.success(MemberResponse.from(member));
    }

    @GetMapping("/csrf")
    public ApiResponse<Void> csrf() {
        return ApiResponse.success();
    }
}
