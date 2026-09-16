package com.npick.clip.application.query.thumbnail;

/**
 * 응답 본문이 될 이미지. storage key 도 서버 경로도 담기지 않는다.
 *
 * <p>영상과 달리 바이트를 그대로 들고 있다. 대표 이미지는 keyframe JPEG 한 장이라 크기가 작고, 헤더를 쓴 뒤 전송이 실패하는 경우를 아예 없앨 수 있기 때문이다 — 그 경우 이미 보낸 길이·형식
 * 헤더 때문에 오류 Envelope 가 잘린다. {@code /media/{clipId}} 는 파일이 커서 이 선택을 할 수 없어 전송 중 실패를 따로 다룬다.
 *
 * @param contentType 파일 머리글로 판별한 이미지 형식
 * @param bytes 이미지 전체
 */
public record SceneThumbnailResult(String contentType, byte[] bytes) {}
