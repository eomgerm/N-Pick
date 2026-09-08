package com.npick.member.presentation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.AuthenticatedMember;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.LoginMember;
import com.npick.member.application.command.login.LoginCommand;
import com.npick.member.application.command.login.LoginUseCase;
import com.npick.member.presentation.request.LoginRequest;
import com.npick.member.presentation.response.MemberResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(LoginUseCase loginUseCase, SecurityContextRepository securityContextRepository) {
        this.loginUseCase = loginUseCase;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/login")
    public ApiResponse<MemberResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        var result = loginUseCase.login(new LoginCommand(request.loginId(), request.password()));
        var principal = new AuthenticatedMember(result.memberId(), result.loginId(), null, result.role());
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());

        httpRequest.getSession(true);
        httpRequest.changeSessionId();

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, httpRequest, httpResponse);

        CurrentMember current = new CurrentMember(principal.memberId(), authentication.getName(), principal.role());
        return ApiResponse.success(MemberResponse.from(current));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.success();
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
