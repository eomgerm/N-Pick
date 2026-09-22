package com.npick.search.application.port;

/**
 * 「내 검색 기록」에서 기록을 숨긴다 (S15P21A501-276).
 *
 * <p>이름이 delete 가 아닌 이유는 <b>행을 지우지 않기</b> 때문이다. {@code search_execution} 은 감사 조회와 신고·검수가 함께 읽는 과거 실행 snapshot 이라, 지우면
 * 문의 상세가 당시 결과를 잃는다. 사용자에게는 삭제로 보이고 저장은 보존하는 것이 이 포트의 계약이다.
 */
public interface SearchHistorySoftDeletePort {

    /**
     * 본인이 실행한 기록 하나를 숨긴다.
     *
     * <p><b>이미 숨긴 기록도 참을 돌려준다.</b> 사용자가 같은 요청을 두 번 보내는 것은 오류가 아니며, 두 번째만 404 가 나가면 FE 가 재시도할 수 없다.
     *
     * @return 숨겼거나 이미 숨겨져 있으면 {@code true}. 없거나 남의 기록이거나 이 화면 대상이 아니면 {@code false}
     */
    boolean hideOwned(long searchExecutionId, long ownerId);
}
