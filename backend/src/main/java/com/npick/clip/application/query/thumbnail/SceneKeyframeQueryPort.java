package com.npick.clip.application.query.thumbnail;

/** scene_id → 대표 keyframe 의 storage key. Aggregate 가 아니라 한 칸짜리 projection 이다(설계 정본 §9). */
public interface SceneKeyframeQueryPort {

    /**
     * 장면의 대표 keyframe 을 찾는다. 대표는 {@code timestamp_ms} 가 가장 이른 프레임이다 (FRD F-03).
     *
     * <p>«장면이 없다» 와 «장면은 있는데 keyframe 이 없다» 를 한 번의 조회로 구분해 돌려준다. 둘을 나눠 물으면 그 사이에 처리가 끝나 앞의 답과 뒤의 답이 서로 다른 시점을 보게 된다.
     */
    SceneKeyframeSource findRepresentativeKeyframe(long sceneId);
}
