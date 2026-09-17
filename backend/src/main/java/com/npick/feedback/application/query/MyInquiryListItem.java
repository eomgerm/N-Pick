package com.npick.feedback.application.query;

import java.time.Instant;

/**
 * 검색 화면 사이드바의 「내 문의 기록」 목록 한 줄 (S15P21A501-185).
 *
 * <p>검수 목록({@link InquiryListItem})과 달리 <b>본인(created_by_id) 소유</b> 문의만 대상이며, FE 소비용 필드(검색 실행·결과 id,
 * 갱신 시각, 원문 comment)를 함께 싣는다. {@code comment}·{@code resolution}은 nullable 이다.
 */
public record MyInquiryListItem(
        long feedbackId,
        long searchExecutionId,
        long searchResultId,
        Instant createdAt,
        Instant updatedAt,
        String queryText,
        String comment,
        String status,
        String resolution,
        InquiryScene scene) {}
