package com.npick.feedback.application.query;

import java.util.List;

/** 목록 한 페이지. totalElements는 현재 필터 기준, statusCounts는 필터 무관 전체 집계. */
public record InquiryListPage(List<InquiryListItem> items, long totalElements, StatusCounts statusCounts) {}
