package com.npick.feedback.application.query;

import java.util.List;

/** 「내 문의 기록」 목록 한 페이지 (S15P21A501-185). totalElements 는 해당 소유자 전체 건수다. */
public record MyInquiryListPage(List<MyInquiryListItem> items, long totalElements) {}
