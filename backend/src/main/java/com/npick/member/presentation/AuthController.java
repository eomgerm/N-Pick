package com.npick.member.presentation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npick.common.response.ApiResponse;
import com.npick.common.security.AuthenticatedMember;
import com.npick.common.security.CurrentMember;
import com.npick.common.security.resolver.CurrentUser;
import com.npick.member.domain.error.MemberAuthException;
import com.npick.member.domain.error.MemberErrorCode;
import com.npick.member.presentation.request.LoginRequest;
import com.npick.member.presentation.response.MemberResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(
            AuthenticationManager authenticationManager, SecurityContextRepository securityContextRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/login")
    public ApiResponse<MemberResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.loginId(), request.password()));
        } catch (BadCredentialsException | UsernameNotFoundException ex) {
            throw new MemberAuthException(MemberErrorCode.INVALID_CREDENTIALS);
        }

        httpRequest.getSession(true);
        httpRequest.changeSessionId();

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, httpRequest, httpResponse);

        AuthenticatedMember principal = (AuthenticatedMember) authentication.getPrincipal();
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
    public ApiResponse<MemberResponse> me(@CurrentUser CurrentMember member) {
        return ApiResponse.success(MemberResponse.from(member));
    }

    @GetMapping("/csrf")
    public ApiResponse<Void> csrf() {
        return ApiResponse.success();
    }
}
