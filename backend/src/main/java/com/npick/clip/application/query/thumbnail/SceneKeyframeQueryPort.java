package com.npick.clip.application.query.thumbnail;

/** scene_id → 대표 keyframe 의 storage key. Aggregate 가 아니라 한 칸짜리 projection 이다(설계 정본 §9). */
public interface SceneKeyframeQueryPort {

    /**
     * 장면의 대표 keyframe 을 찾는다. 대표는 AI 가 선명도로 골라 목록 첫 원소로 보낸 프레임이며, BE 가 그 순서대로 저장하므로 그 장면의 최소 {@code keyframe_id} 다
     * ({@code docs/contracts/job-api.md} §4.3.1).
     *
     * <p>«장면이 없다» 와 «장면은 있는데 keyframe 이 없다» 를 한 번의 조회로 구분해 돌려준다. 둘을 나눠 물으면 그 사이에 처리가 끝나 앞의 답과 뒤의 답이 서로 다른 시점을 보게 된다.
     */
    SceneKeyframeSource findRepresentativeKeyframe(long sceneId);
}
