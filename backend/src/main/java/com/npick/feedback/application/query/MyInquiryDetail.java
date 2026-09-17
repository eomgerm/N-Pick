package com.npick.feedback.application.query;

import java.time.Instant;

/**
 * 「내 문의 기록」 상세 (S15P21A501-185). 목록 항목({@link MyInquiryListItem})의 필드에 처리 사유·시각과 검색 실행 당시
 * explicit_filters(JSON 원문)를 더한다. 본인(created_by_id) 소유 조건은 조회 계층에서 건다.
 */
public record MyInquiryDetail(
        long feedbackId,
        long searchExecutionId,
        long searchResultId,
        Instant createdAt,
        Instant updatedAt,
        String queryText,
        String comment,
        String status,
        String resolution,
        InquiryScene scene,
        String explicitFiltersJson,
        String resolutionNote,
        Instant reviewStartedAt,
        Instant closedAt) {}
