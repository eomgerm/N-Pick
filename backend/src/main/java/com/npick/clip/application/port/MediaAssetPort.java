package com.npick.clip.application.port;

import java.io.OutputStream;
import java.util.Optional;

/**
 * storage key 로 재생 대상을 여는 계약 (FR-RES-013).
 *
 * <p>서버 절대 경로는 어댑터 안에 머문다. application 은 크기·형식과 "이만큼 써 달라"는 요청만 다룬다.
 */
public interface MediaAssetPort {

    /**
     * media root 안에서 storage key 를 해석한다.
     *
     * @throws com.npick.common.error.BusinessException media root 를 벗어난 key, 없는 파일, 읽기 실패
     */
    MediaAsset resolve(String storageKey);

    interface MediaAsset extends AutoCloseable {

        String contentType();

        long sizeBytes();

        /** 프록시가 직접 전송할 수 있는 내부 위치. 비어 있으면 애플리케이션이 바이트를 직접 쓴다. */
        Optional<String> internalLocation();

        /** {@code offset} 부터 {@code count} 바이트를 쓴다. {@code target} 은 닫지 않는다. */
        void writeTo(OutputStream target, long offset, long count);

        /** 임시 산출물이 있으면 정리한다. 영속 원본 asset 은 기본적으로 할 일이 없다. */
        @Override
        default void close() {}
    }
}
