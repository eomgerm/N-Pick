package com.npick.feedback.application.query;

import java.util.List;

/**
 * 「내 문의 기록」 목록 조회 (S15P21A501-185). 본인(created_by_id) 소유 문의만 최신 접수순으로 페이지 단위 조회한다.
 *
 * <p>검수 큐({@link InquiryListQuery})와 조회 조건이 다르다 — 상태 필터가 아니라 소유자 조건이고, 상태 탭 배지 집계도 쓰지 않는다.
 */
public interface MyInquiryListQuery {

    List<MyInquiryListItem> findByOwner(long ownerId, int page, int size);

    /** 해당 소유자의 전체 문의 건수(페이지네이션용). */
    long countByOwner(long ownerId);
}
