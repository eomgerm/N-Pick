package com.npick.search.domain.repository;

import java.util.Optional;

import com.npick.search.domain.model.SceneExcludeCandidate;

/**
 * 장면 제외 후보를 쓴다. 활성 규칙을 읽는 {@link ParseRuleRepository} 와 책임이 다르므로 별도 포트로 둔다.
 *
 * <p>후보는 {@code active=false} 로만 저장된다. 켜는 것(-85)과 검색 시 적용(-58)은 이 포트의 일이 아니다.
 */
public interface SceneExcludeCandidateRepository {

    /**
     * 후보를 저장하고 생성된 id 를 준다. 같은 신고·대상 장면의 후보가 이미 있으면 아무것도 하지 않고 {@link Optional#empty()} 를 준다.
     *
     * <p>{@code ON CONFLICT (source_feedback_id, target_scene_id) DO NOTHING} 으로 충돌을 예외 없이 흡수한다 — 어떤 propagation 에서도
     * 안전하고 동시 재시도가 500 이 되지 않는다. exclude 후보는 장면당 하나뿐이라 request_key 가 달라진 재시도도 중복을 만들지 않는다.
     */
    Optional<Long> insertIfAbsent(SceneExcludeCandidate candidate);

    /** 신고의 그 장면에 대한 기존 제외 후보 id. 멱등 재생·경합 복구에 쓴다. */
    Optional<Long> findByTargetScene(long sourceFeedbackId, long targetSceneId);
}
