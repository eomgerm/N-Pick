package com.npick.clip.infrastructure.transcript;

/** readNBytes(limit + 1)에 사용하는 자막 바이트 상한의 공통 검증. */
public final class SubtitleLimits {
    private SubtitleLimits() {}

    public static int requireValid(int maxBytes) {
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("자막 크기 제한은 1 이상 Integer.MAX_VALUE 미만이어야 합니다.");
        }
        return maxBytes;
    }
}
