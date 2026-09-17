package com.npick.clip.application.query.thumbnail;

/**
 * 대표 keyframe 조회 결과.
 *
 * @param sceneFound 조회 가능한 장면이 있었는지
 * @param storageKey media root 기준 상대 key. 장면에 keyframe 이 없으면 {@code null}
 */
public record SceneKeyframeSource(boolean sceneFound, String storageKey) {

    public static SceneKeyframeSource sceneMissing() {
        return new SceneKeyframeSource(false, null);
    }

    public static SceneKeyframeSource keyframeMissing() {
        return new SceneKeyframeSource(true, null);
    }

    public static SceneKeyframeSource of(String storageKey) {
        return new SceneKeyframeSource(true, storageKey);
    }
}
