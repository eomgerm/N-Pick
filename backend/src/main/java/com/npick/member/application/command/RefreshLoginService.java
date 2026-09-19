package com.npick.member.application.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.error.CommonErrorCode;
import com.npick.member.application.command.login.LoginResult;
import com.npick.member.application.command.login.RefreshLoginUseCase;
import com.npick.member.application.command.login.RegisterRefreshUseCase;
import com.npick.member.application.command.login.RevokeRefreshUseCase;
import com.npick.member.application.port.AccessSessionPort;
import com.npick.member.application.port.RefreshSessionStore;
import com.npick.member.application.port.RefreshSessionStore.StoredRefresh;
import com.npick.member.domain.repository.MemberRepository;

@Service
public class RefreshLoginService implements RefreshLoginUseCase, RegisterRefreshUseCase, RevokeRefreshUseCase {
    private final RefreshSessionStore refreshes;
    private final AccessSessionPort access;
    private final MemberRepository members;
    private final Duration lifetime;
    private final SecureRandom random = new SecureRandom();

    public RefreshLoginService(
            RefreshSessionStore refreshes,
            AccessSessionPort access,
            MemberRepository members,
            @Value("${npick.auth.refresh-ttl:8h}") Duration lifetime) {
        this.refreshes = refreshes;
        this.access = access;
        this.members = members;
        this.lifetime = lifetime;
    }

    @Override
    @Transactional
    public IssuedRefresh register(String accessSessionId, LoginResult member) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Instant now = Instant.now();
        Instant expires = now.plus(lifetime);
        refreshes.deleteExpired(now);
        refreshes.insert(new StoredRefresh(hash(token), member.loginId(), accessSessionId, expires));
        return new IssuedRefresh(token, expires);
    }

    @Override
    @Transactional
    public RefreshedLogin refresh(String token) {
        var stored = refreshes.lock(hash(token)).orElseThrow(RefreshLoginService::unauthorized);
        if (!stored.expiresAt().isAfter(Instant.now())) throw unauthorized();
        var member = members.findByLoginId(stored.loginId()).orElseThrow(RefreshLoginService::unauthorized);
        var result = new LoginResult(
                member.memberId(), member.loginId(), member.role().name());
        // 동시 갱신은 같은 행의 잠금으로 직렬화한다. 이미 발급된 유효 access를 재사용한다.
        // refresh 자체의 만료 시각은 로그인 시점에 고정하며 여기서 늘리지 않는다.
        if (access.isActive(stored.accessSessionId())) {
            return new RefreshedLogin(stored.accessSessionId(), result);
        }
        access.delete(stored.accessSessionId());
        String id = access.create(result, stored.expiresAt());
        refreshes.replaceAccess(stored.tokenHash(), id);
        return new RefreshedLogin(id, result);
    }

    @Override
    @Transactional
    public void revoke(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) return;
        refreshes.lock(hash(token)).ifPresent(stored -> {
            access.delete(stored.accessSessionId());
            refreshes.delete(stored.tokenHash());
        });
    }

    private static String hash(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) throw unauthorized();
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED);
    }
}
