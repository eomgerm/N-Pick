package com.npick.search.application;

/**
 * 검수자가 만든 비활성 장면 제외 후보를 저장한다 (S15P21A501-82, F-11).
 */
public interface CreateSceneExcludeCandidateUseCase {

    ParseCandidateOutcome create(CreateSceneExcludeCandidateCommand command);
}
