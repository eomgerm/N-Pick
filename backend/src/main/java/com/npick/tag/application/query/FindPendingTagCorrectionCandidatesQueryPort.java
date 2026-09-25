package com.npick.tag.application.query;

import java.util.List;

/** 대기 중인 검수자 태그 교정 근거를 태그·태깅과 함께 읽는 조회 계약 (S15P21A501-317, 설계 정본 §9 의 QueryPort). */
public interface FindPendingTagCorrectionCandidatesQueryPort {

    List<PendingTagCorrectionCandidate> findPending(long sourceFeedbackId);
}
