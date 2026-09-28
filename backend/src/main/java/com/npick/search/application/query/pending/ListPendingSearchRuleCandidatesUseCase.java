package com.npick.search.application.query.pending;

/**
 * 이 신고가 만든 대기 중인 해석·장면 제외 규칙 후보 — search 도메인이 공개하는 조회 UseCase (S15P21A501-317).
 *
 * <p>feedback 오케스트레이터가 새로고침 뒤 검수자의 작성 중 교정을 복원할 때 부른다. 담당 검수자 확인은 호출부가 한다.
 *
 * <p>{@code active=false} 는 대기 후보뿐 아니라 확정 뒤 사용 중단·교체된 규칙도 뜻한다. 후보로 읽을 수 있는 건 신고가 검수 중일 때뿐이라 상태 확인도 호출부가 한다.
 */
public interface ListPendingSearchRuleCandidatesUseCase {

    /** @return {@code active=false} 후보를 만든 순서대로 */
    PendingSearchRuleCandidates listPending(long sourceFeedbackId);
}
