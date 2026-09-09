package com.npick.feedback.application.query;

import java.util.List;

public interface InquiryListQuery {
    List<InquiryListItem> findByStatus(String statusOrNull, int page, int size);

    /** 현재 필터 기준 전체 건수(페이지네이션용). */
    long countByStatus(String statusOrNull);

    /** 필터와 무관한 상태별 전체 건수(상태 탭 배지용). */
    StatusCounts countGroupedByStatus();
}
