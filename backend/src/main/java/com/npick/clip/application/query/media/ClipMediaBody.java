package com.npick.clip.application.query.media;

import java.io.OutputStream;

/** 응답 본문 전송. 보낼 구간은 이미 결정되어 있어 호출자가 offset 을 다시 계산하지 않는다. */
@FunctionalInterface
public interface ClipMediaBody {

    void writeTo(OutputStream target);
}
