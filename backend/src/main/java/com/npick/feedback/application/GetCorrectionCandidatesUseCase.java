package com.npick.feedback.application;

import com.npick.feedback.application.query.CorrectionCandidates;

/** 담당 검수자가 이 신고의 대기 중인 교정 후보 전체를 읽는다 (S15P21A501-317, F-10·F-11). */
public interface GetCorrectionCandidatesUseCase {

    CorrectionCandidates get(long feedbackId, long reviewerId);
}
