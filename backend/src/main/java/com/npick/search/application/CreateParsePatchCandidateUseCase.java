package com.npick.search.application;

/**
 * 검수자가 만든 비활성 patch_parse 규칙 후보를 저장한다 (S15P21A501-81, F-11).
 */
public interface CreateParsePatchCandidateUseCase {

    ParseCandidateOutcome create(CreateParsePatchCandidateCommand command);
}
