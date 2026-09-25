package com.npick.tag.application.query;

import java.util.List;

/**
 * 이 신고가 만든 대기 중인 태그 교정 근거 목록 — tag 도메인이 공개하는 조회 UseCase (S15P21A501-317).
 *
 * <p>feedback 오케스트레이터가 새로고침 뒤 검수자의 작성 중 교정을 복원할 때 부른다. 담당 검수자 확인은 호출부가 한다.
 */
public interface ListPendingTagCorrectionCandidatesUseCase {

    /** @return {@code source='reviewer_feedback'}·{@code confirmed=false} 근거를 만든 순서대로 */
    List<PendingTagCorrectionCandidate> listPending(long sourceFeedbackId);
}
