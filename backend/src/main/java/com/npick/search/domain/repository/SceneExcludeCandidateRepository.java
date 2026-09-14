package com.npick.search.domain.repository;

import java.util.Optional;

import com.npick.search.domain.model.SceneExcludeCandidate;

/**
 * 장면 제외 후보를 쓴다. 활성 규칙을 읽는 {@link ParseRuleRepository} 와 책임이 다르므로 별도 포트로 둔다.
 *
 * <p>후보는 {@code active=false} 로만 저장된다. 켜는 것(-85)과 검색 시 적용(-58)은 이 포트의 일이 아니다.
 */
public interface SceneExcludeCandidateRepository {

    long save(SceneExcludeCandidate candidate);

    Optional<Long> findId(long sourceFeedbackId, String requestKey);
}
