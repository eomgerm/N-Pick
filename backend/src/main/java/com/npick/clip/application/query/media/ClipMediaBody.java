package com.npick.clip.application.query.media;

import java.io.OutputStream;

/** 응답 본문 전송. 보낼 구간은 이미 결정되어 있어 호출자가 offset 을 다시 계산하지 않는다. */
public interface ClipMediaBody extends AutoCloseable {

    void writeTo(OutputStream target);

    /** 본문을 쓰기 전에 응답 준비가 실패해도 임시 산출물을 정리한다. */
    @Override
    default void close() {}
}
