package com.npick.clip.application.query.media;

import java.util.Optional;

/** clip_id → media root 기준 storage key. Aggregate 가 아니라 재생에 필요한 한 칸만 읽는 projection 이다. */
public interface ClipStorageKeyQueryPort {

    /** 삭제되지 않은 영상의 storage key. 없으면 비어 있다. */
    Optional<String> findPlayableStorageKey(long clipId);
}
