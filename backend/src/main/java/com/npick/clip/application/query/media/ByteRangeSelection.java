package com.npick.clip.application.query.media;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.common.error.BusinessException;

/**
 * Range 요청이 지정한 전송 구간 (FR-RES-014).
 *
 * <p>RFC 9110 을 따른다. 이해할 수 없는 range unit 은 무시하고 전체를 보낸다(§14.2 의 MUST). 문법이 깨진 bytes 범위는 거부한다(같은 절의 "MAY ... reject").
 * 여러 구간을 한 응답에 담는 multipart/byteranges 는 제공하지 않으므로 무시하고 전체를 보낸다. 만족시킬 수 없는 구간만 416 이다(§15.5.17).
 *
 * <p>거부는 전체 전송으로 조용히 넘기지 않고 {@link ClipMediaErrorCode#RANGE_NOT_SATISFIABLE} 로 구분해 알린다 — 구간 이동 실패를 사용자에게 안내해야 한다(FRD
 * F-07).
 *
 * @param offset 보낼 구간의 시작 바이트
 * @param length 보낼 바이트 수
 * @param partial Range 가 적용되었는지
 */
public record ByteRangeSelection(long offset, long length, boolean partial) {

    private static final String BYTES_UNIT = "bytes=";

    public static ByteRangeSelection of(String rangeHeader, long totalBytes) {
        if (totalBytes < 0) {
            throw new BusinessException(ClipMediaErrorCode.MEDIA_READ_FAILED);
        }
        String header = rangeHeader == null ? "" : rangeHeader.trim();
        if (header.isEmpty() || !header.regionMatches(true, 0, BYTES_UNIT, 0, BYTES_UNIT.length())) {
            return whole(totalBytes);
        }
        String spec = header.substring(BYTES_UNIT.length()).trim();
        if (spec.indexOf(',') >= 0) {
            return whole(totalBytes);
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw unsatisfiable();
        }
        String firstPosition = spec.substring(0, dash).trim();
        String lastPosition = spec.substring(dash + 1).trim();
        if (firstPosition.isEmpty()) {
            return suffix(position(lastPosition), totalBytes);
        }
        long start = position(firstPosition);
        if (start >= totalBytes) {
            throw unsatisfiable();
        }
        long end = lastPosition.isEmpty() ? totalBytes - 1 : Math.min(position(lastPosition), totalBytes - 1);
        if (end < start) {
            throw unsatisfiable();
        }
        return new ByteRangeSelection(start, end - start + 1, true);
    }

    /** {@code bytes=-N} 은 마지막 N 바이트다. N 이 파일보다 크면 전체를 구간으로 보낸다. */
    private static ByteRangeSelection suffix(long length, long totalBytes) {
        if (length <= 0 || totalBytes == 0) {
            throw unsatisfiable();
        }
        long start = Math.max(0, totalBytes - length);
        return new ByteRangeSelection(start, totalBytes - start, true);
    }

    private static ByteRangeSelection whole(long totalBytes) {
        return new ByteRangeSelection(0, totalBytes, false);
    }

    /** 부호·공백·빈 문자열과 long 범위를 넘는 자릿수를 모두 거부한다. */
    private static long position(String value) {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw unsatisfiable();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw unsatisfiable();
        }
    }

    private static BusinessException unsatisfiable() {
        return new BusinessException(ClipMediaErrorCode.RANGE_NOT_SATISFIABLE);
    }
}
