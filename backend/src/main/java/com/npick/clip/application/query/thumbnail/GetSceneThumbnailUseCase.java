package com.npick.clip.application.query.thumbnail;

/** 장면 하나의 대표 이미지를 연다. 입력이 scene_id 하나뿐이라 Query 객체를 만들지 않는다(설계 정본 §16). */
public interface GetSceneThumbnailUseCase {

    SceneThumbnailResult get(long sceneId);
}
