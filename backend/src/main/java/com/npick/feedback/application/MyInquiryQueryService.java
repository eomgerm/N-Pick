package com.npick.feedback.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.application.query.MyInquiryListPage;
import com.npick.feedback.application.query.MyInquiryListQuery;

/**
 * 검색 화면용 「내 문의 기록」 조회 서비스 (S15P21A501-185). 본인 소유 문의만 읽는 읽기 전용 경로다.
 */
@Service
public class MyInquiryQueryService {

    private final MyInquiryListQuery listQuery;

    public MyInquiryQueryService(MyInquiryListQuery listQuery) {
        this.listQuery = listQuery;
    }

    @Transactional(readOnly = true)
    public MyInquiryListPage listMine(long ownerId, int page, int size) {
        return new MyInquiryListPage(
                listQuery.findByOwner(ownerId, page, size), listQuery.countByOwner(ownerId));
    }
}
