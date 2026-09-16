package com.npick.search.domain.model;

/**
 * 후보에서 제외된 이유. F-05 7항·F-06 완료 기준의 제외 사유 기록이며 #60이 explain_json으로 남긴다. 명시 조건과 충돌해서 떨어진 결과가 아니라 검색 대상 자체가 아닌 장면의 사유다.
 */
public enum IneligibleReason {
    /** 요청한 sceneId의 장면이 없다. 이미 지워졌거나 후보 목록이 낡았다. */
    SCENE_NOT_FOUND,
    /** 장면이 속한 클립이 논리 삭제됐다. */
    CLIP_DELETED,
    /** 장면이 클립의 활성 처리 소속이 아니다. 재처리로 대체된 처리의 장면이 여기 해당한다. */
    INACTIVE_RUN
}
