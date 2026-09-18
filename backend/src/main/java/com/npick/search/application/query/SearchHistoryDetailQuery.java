package com.npick.search.application.query;

import java.util.Optional;

/**
 * 「내 검색 기록」상세 조회 포트 (S15P21A501-198).
 *
 * <p>목록과 <b>같은</b> 소유·대상 조건을 쓴다. 타인 소유·미존재·대상 밖(replay 등)을 구분하지 않고 빈 값으로 돌려
 * 존재 여부를 노출하지 않는다 — 호출자가 동일한 404 로 바꾼다.
 */
public interface SearchHistoryDetailQuery {

    Optional<SearchHistoryRecord> findByOwner(long searchExecutionId, long ownerId);
}
