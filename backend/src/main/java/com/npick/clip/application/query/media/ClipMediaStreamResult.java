package com.npick.clip.application.query.media;

/**
 * @param totalBytes 영상 전체 길이. Range 응답의 분모다
 * @param offset 보낼 구간의 시작 바이트
 * @param length 보낼 바이트 수
 * @param partial Range 가 적용되었는지. 전체 전송이면 {@code false}
 * @param internalLocation 프록시에 위임할 내부 위치. {@code null} 이면 {@code body} 로 직접 전송한다
 */
public record ClipMediaStreamResult(
        String contentType,
        long totalBytes,
        long offset,
        long length,
        boolean partial,
        String internalLocation,
        ClipMediaBody body) {}
