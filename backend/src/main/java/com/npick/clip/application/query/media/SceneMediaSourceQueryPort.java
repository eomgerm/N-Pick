package com.npick.clip.application.query.media;

import java.util.Optional;

/** scene_id로 다운로드할 원본 key와 저장된 장면 경계를 읽는 projection. */
public interface SceneMediaSourceQueryPort {

    /** 삭제되지 않은 영상에 속한 장면만 반환한다. */
    Optional<SceneMediaSource> findDownloadSource(long sceneId);
}
