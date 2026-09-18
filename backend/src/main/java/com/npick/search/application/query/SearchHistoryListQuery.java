package com.npick.search.application.query;

import java.util.List;

/**
 * 「내 검색 기록」목록 조회 포트 (S15P21A501-198).
 *
 * <p>조회 범위는 <b>세션 사용자가 직접 실행한 original 중 succeeded/degraded</b> 다. replay·running·failed 는 DB 에
 * 보존하되 이 화면에서 제외한다(S15P21A501-185). 목록과 총계가 같은 조건을 쓰지 않으면 노출 건수와 총계가 어긋난다.
 */
public interface SearchHistoryListQuery {

    /** 최신순(created_at DESC, search_execution_id DESC) 한 페이지. 각 실행의 결과를 전량 싣는다. */
    List<SearchHistoryRecord> findByOwner(long ownerId, int page, int size);

    long countByOwner(long ownerId);
}
