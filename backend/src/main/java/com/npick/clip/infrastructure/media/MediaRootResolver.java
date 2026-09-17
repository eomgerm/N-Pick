package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import com.npick.common.error.BusinessException;
import com.npick.common.error.ErrorCode;

/**
 * media root 경계. 저장된 파일을 여는 모든 경로가 이 한 곳을 지난다.
 *
 * <p>영상 재생과 장면 대표 이미지는 실패 어휘가 다르지만 «root 를 벗어나면 파일이 있어도 거부한다» 는 규칙은 하나다 (FRD §6.4). 규칙을 어댑터마다 다시 쓰면 한쪽만 고쳐져 갈라진다 — 갈라진
 * 쪽이 경로 이탈을 허용해도 다른 쪽 테스트는 초록이다. 그래서 규칙은 여기 한 벌만 두고, 어댑터는 자기 ErrorCode 만 들려 준다.
 */
final class MediaRootResolver {

    private final Path mediaRoot;
    private final Failures failures;

    MediaRootResolver(Path mediaRoot, Failures failures) {
        this.mediaRoot = mediaRoot;
        this.failures = failures;
    }

    /**
     * storage key 를 media root 안의 실제 파일로 해석한다.
     *
     * <p>정규화 후에도, 심볼릭 링크를 따라간 실제 위치도 root 안이어야 한다. 거부할 때는 파일이 있었는지조차 알려 주지 않는다.
     */
    Resolved resolve(String storageKey) {
        Path root = realRoot();
        Path candidate = insideRoot(root, storageKey);
        Path real = realPathOf(candidate);
        if (!real.startsWith(root)) {
            throw new BusinessException(failures.locationRejected());
        }
        return new Resolved(root, candidate, real);
    }

    private Path realRoot() {
        try {
            return mediaRoot.toRealPath();
        } catch (IOException unavailable) {
            throw new BusinessException(failures.rootUnavailable(), unavailable);
        }
    }

    /** {@code resolve} 는 절대 경로 key 를 그대로 채택하므로 정규화 후 root 포함 여부를 반드시 확인한다. */
    private Path insideRoot(Path root, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(failures.locationRejected());
        }
        Path candidate;
        try {
            candidate = root.resolve(storageKey).normalize();
        } catch (InvalidPathException rejected) {
            throw new BusinessException(failures.locationRejected(), rejected);
        }
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            throw new BusinessException(failures.locationRejected());
        }
        return candidate;
    }

    private Path realPathOf(Path candidate) {
        try {
            return candidate.toRealPath();
        } catch (NoSuchFileException missing) {
            throw new BusinessException(failures.fileMissing(), missing);
        } catch (IOException failure) {
            throw new BusinessException(failures.readFailed(), failure);
        }
    }

    /**
     * @param root 링크를 펼친 media root
     * @param candidate root 기준으로 정규화한 위치. 링크는 아직 펼치지 않았다
     * @param real 링크까지 펼친 실제 파일 위치
     */
    record Resolved(Path root, Path candidate, Path real) {}

    /** 같은 규칙을 기능별 어휘로 말하기 위한 ErrorCode 묶음 (설계 정본 §13). */
    record Failures(
            ErrorCode rootUnavailable, ErrorCode locationRejected, ErrorCode fileMissing, ErrorCode readFailed) {}
}
