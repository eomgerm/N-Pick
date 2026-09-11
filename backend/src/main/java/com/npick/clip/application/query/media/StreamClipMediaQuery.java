package com.npick.clip.application.query.media;

/**
 * @param clipId 재생할 영상. 경로가 아니라 ID 로만 지정한다 (FR-RES-013)
 * @param rangeHeader HTTP Range 헤더 원문. 없으면 {@code null}
 */
public record StreamClipMediaQuery(long clipId, String rangeHeader) {}
