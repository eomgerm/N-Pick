package com.npick.tag.application;

/** 검수자가 만든 태그 교정 후보를 저장하는 UseCase (S15P21A501-160, F-10). */
public interface CreateTagCorrectionCandidateUseCase {

    CreateTagCorrectionCandidateResult create(CreateTagCorrectionCandidateCommand command);
}
