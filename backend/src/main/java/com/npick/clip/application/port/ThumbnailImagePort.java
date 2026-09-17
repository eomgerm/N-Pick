package com.npick.clip.application.port;

/**
 * storage key 로 대표 이미지를 읽는 계약 (FRD F-03·F-07).
 *
 * <p>{@link MediaAssetPort} 와 나눈 이유는 두 계약이 서로 다른 것을 약속하기 때문이다. 재생은 「구간을 열어 준다」 이고 형식은 등록이 허용한 mp4·mov 컨테이너다. 썸네일은 「한 장을
 * 통째로 준다」 이고 형식은 AI 가 남긴 이미지다. 한 Port 로 합치면 어느 한쪽의 형식 판별이 다른 쪽의 응답 Content-Type 을 틀리게 만든다.
 *
 * <p>서버 절대 경로는 어댑터 안에 머문다.
 */
public interface ThumbnailImagePort {

    /**
     * media root 안에서 storage key 를 해석해 이미지를 읽는다.
     *
     * @throws com.npick.common.error.BusinessException media root 를 벗어난 key, 없는 파일, 읽기 실패
     */
    ThumbnailImage read(String storageKey);

    /**
     * @param contentType 파일 머리글로 판별한 형식
     * @param bytes 이미지 전체
     */
    record ThumbnailImage(String contentType, byte[] bytes) {}
}
